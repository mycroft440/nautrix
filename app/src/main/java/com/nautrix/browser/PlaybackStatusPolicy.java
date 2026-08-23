package com.nautrix.browser;

/** Pure status policy so buffering feedback can be tested without Android dependencies. */
public final class PlaybackStatusPolicy {
    public static final long INITIAL_SLOW_THRESHOLD_MS = 8_000L;
    public static final long REBUFFER_SLOW_THRESHOLD_MS = 5_000L;

    public enum Status {
        OFFLINE,
        CONNECTING,
        BUFFERING,
        SLOW_SERVER
    }

    private PlaybackStatusPolicy() {
    }

    public static Status bufferingStatus(boolean connected, boolean playedBefore,
                                         long bufferingMillis) {
        if (!connected) return Status.OFFLINE;
        long threshold = playedBefore ? REBUFFER_SLOW_THRESHOLD_MS : INITIAL_SLOW_THRESHOLD_MS;
        if (Math.max(0L, bufferingMillis) >= threshold) return Status.SLOW_SERVER;
        return playedBefore ? Status.BUFFERING : Status.CONNECTING;
    }

    public static String message(Status status) {
        switch (status) {
            case OFFLINE:
                return "Sem conexão com a internet";
            case BUFFERING:
                return "Carregando mais vídeo…";
            case SLOW_SERVER:
                return "Servidor do site lento!";
            case CONNECTING:
            default:
                return "Conectando ao vídeo…";
        }
    }
}
