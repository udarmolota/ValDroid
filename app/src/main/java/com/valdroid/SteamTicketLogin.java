package com.valdroid;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.handlers.steamauthticket.SteamAuthTicket;
import in.dragonbra.javasteam.steam.handlers.steamauthticket.TicketInfo;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.AccountInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;

/**
 * Crossplay on the native engine: the Steam side of Valheim's PlayFab login, done by the launcher.
 * Valheim logs in to PlayFab with a Steam session ticket (PlayFabAuthWithSteam), and there is no Steam
 * client on the phone, so before the launch this signs in with JavaSteam (approved in Steam Mobile, as
 * for the cloud saves; the password is used for this sign-in only and never stored), asks Steam for a
 * session ticket for Valheim and writes it to a file. The game's libsteam_api stub hands it to the game
 * (VALDROID_STEAM_TICKET, see tools/steam-stub-arm64/gen_steam_stub.py) together with the real SteamID
 * and name. Steam drops a session's tickets when it signs off, so the connection stays up for
 * {@link #HOLD_MS} after the launch, long enough for the game to start and PlayFab to check the ticket,
 * and then closes. The ticket itself is never logged.
 */
public class SteamTicketLogin implements Runnable {
    private static final String TAG = "ValDroid/Crossplay";
    private static final int VALHEIM = 892970;
    /** How long the Steam session outlives the ticket: game start (about a minute) plus PlayFab's check. */
    public static final long HOLD_MS = 5 * 60 * 1000L;

    /** What the game gets: its SteamID and name, and the file the ticket is in. */
    public static final class Result {
        public final long steamId;
        public final String persona;   // null when Steam did not send it in time
        public final File ticketFile;
        Result(long steamId, String persona, File ticketFile) {
            this.steamId = steamId; this.persona = persona; this.ticketFile = ticketFile;
        }
    }

    public interface Listener {
        /** Steam Guard code prompt (only if the account is not approved via the Steam Mobile push). */
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        /** A step for the waiting dialog. */
        void onProgress(String message);
        /** The ticket is written: launch the game. The Steam session stays up for HOLD_MS. */
        void onReady(Result result);
        /** Sign-in or ticket failed; nothing was written. */
        void onFailed(String message);
    }

    private final String username, password;
    private final File ticketFile;
    private final Listener listener;
    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private volatile boolean running;
    private volatile boolean started;
    private volatile boolean cancelled;
    private volatile String persona;
    private final CountDownLatch accountInfo = new CountDownLatch(1);

    public SteamTicketLogin(String username, String password, File ticketFile, Listener listener) {
        this.username = username;
        this.password = password;
        this.ticketFile = ticketFile;
        this.listener = listener;
    }

    /** Stops waiting (the player chose to play without crossplay): no ticket, Steam disconnects. */
    public void cancel() {
        cancelled = true;
        running = false;
    }

    @Override
    public void run() {
        try {
            steamClient = new SteamClient();
            // The ticket handler must be there before the logon: the game connect tokens a session
            // ticket is built from arrive with it.
            if (steamClient.getHandler(SteamAuthTicket.class) == null) steamClient.addHandler(new SteamAuthTicket());
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);
            manager.subscribe(ConnectedCallback.class, cb -> onConnected());
            manager.subscribe(DisconnectedCallback.class, cb -> {
                if (!started) fail("Steam: connection lost");
                running = false;
            });
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            manager.subscribe(AccountInfoCallback.class, cb -> {
                persona = cb.getPersonaName();
                accountInfo.countDown();
            });
            running = true;
            listener.onProgress("connect");
            steamClient.connect();
            while (running) manager.runWaitCallbacks(1000L);
        } catch (Throwable t) {
            Log.e(TAG, "Steam sign-in crashed", t);
            fail(String.valueOf(t.getMessage()));
        } finally {
            try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
            Log.i(TAG, "Steam session closed");
        }
    }

    private void onConnected() {
        try {
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.persistentSession = false;
            details.deviceFriendlyName = "ValDroid";
            details.authenticator = new PushAuthenticator();
            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            listener.onProgress("approve");
            AuthPollResult poll = session.pollingWaitForResult().get();
            if (cancelled) { running = false; return; }
            LogOnDetails lod = new LogOnDetails();
            lod.setUsername(poll.getAccountName());
            lod.setAccessToken(poll.getRefreshToken());
            lod.setLoginID(153);   // apart from the downloader's 149 and the cloud's 151
            steamUser.logOn(lod);
        } catch (Throwable t) {
            fail(String.valueOf(t.getMessage()));
            running = false;
        }
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            fail("Steam: " + cb.getResult());
            running = false;
            return;
        }
        synchronized (this) {
            if (started) return;
            started = true;
        }
        new Thread(this::ticket, "SteamTicket").start();
    }

    private void ticket() {
        TicketInfo ticket = null;
        try {
            listener.onProgress("ticket");
            SteamAuthTicket tickets = steamClient.getHandler(SteamAuthTicket.class);
            ticket = tickets.getAuthSessionTicket(VALHEIM).get(30, TimeUnit.SECONDS);
            writeTicket(ticket.getTicket());
            accountInfo.await(5, TimeUnit.SECONDS);   // the name comes in its own message after the logon
            long steamId = steamClient.getSteamID().convertToUInt64();
            Log.i(TAG, "Steam ticket ready (" + ticket.getTicket().length + " bytes), session held "
                    + (HOLD_MS / 60000) + " min");
            if (cancelled) {
                ticketFile.delete();
                return;
            }
            listener.onReady(new Result(steamId, persona, ticketFile));
            // Holding the session keeps the ticket valid while the game starts and logs in.
            long until = System.currentTimeMillis() + HOLD_MS;
            while (!cancelled && System.currentTimeMillis() < until) Thread.sleep(1000);
        } catch (Throwable t) {
            Log.e(TAG, "Steam ticket failed", t);
            ticketFile.delete();
            fail(String.valueOf(t.getMessage()));
        } finally {
            // Not ticket.close(): that would cancel the ticket at Steam while the game may still use it.
            running = false;
        }
    }

    private void writeTicket(byte[] ticket) throws IOException {
        File dir = ticketFile.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        try (FileOutputStream out = new FileOutputStream(ticketFile)) {
            out.write(ticket);
        }
    }

    private void fail(String message) {
        if (cancelled) return;
        Log.w(TAG, "crossplay sign-in failed: " + message);
        listener.onFailed(message);
    }

    private class PushAuthenticator implements IAuthenticator {
        @Override public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        @Override public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            return listener.requestSteamGuardCode(previousCodeWasIncorrect, null);
        }
        @Override public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            return listener.requestSteamGuardCode(previousCodeWasIncorrect, email);
        }
    }
}
