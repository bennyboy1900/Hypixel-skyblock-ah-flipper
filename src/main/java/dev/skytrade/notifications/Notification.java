package dev.skytrade.notifications;

public record Notification(long timestamp, Level level, String title, String message) {
    public enum Level { INFO, SUCCESS, WARNING, ERROR }
}
