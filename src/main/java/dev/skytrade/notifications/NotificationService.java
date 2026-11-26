package dev.skytrade.notifications;

import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.BazaarSnapshot;
import dev.skytrade.strategy.Opportunity;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.RingBuffer;
import dev.skytrade.util.TimeSource;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Collects notifications from background threads. The client thread drains {@link #poll()} once per
 * tick and shows them as chat lines / toasts, so no game state is touched off-thread.
 */
public final class NotificationService {
    private final Queue<Notification> pending = new ConcurrentLinkedQueue<>();
    private final RingBuffer<Notification> history = new RingBuffer<>(200);
    private final Supplier<SkyTradeConfig> config;
    private final TimeSource time;
    private final Map<String, Long> lastAlert = new HashMap<>();

    public NotificationService(Supplier<SkyTradeConfig> config, TimeSource time) {
        this.config = config;
        this.time = time;
    }

    public void post(Notification.Level level, String title, String message) {
        Notification n = new Notification(time.now(), level, title, message);
        history.add(n);
        if (pending.size() < 50) pending.add(n);
    }

    public Notification poll() {
        return pending.poll();
    }

    public List<Notification> recent() {
        List<Notification> l = history.toList();
        Collections.reverse(l);
        return l;
    }

    /** Checks user price alerts against a new bazaar snapshot. */
    public void checkPriceAlerts(BazaarSnapshot snap) {
        for (SkyTradeConfig.PriceAlert a : config.get().notifications.priceAlerts) {
            if (a.triggered || a.productId == null) continue;
            BazaarProduct p = snap.get(a.productId);
            if (p == null) continue;
            double price = a.useBuyPrice ? p.bestAsk() : p.bestBid();
            boolean hit = "ABOVE".equalsIgnoreCase(a.direction) ? price >= a.price : price <= a.price;
            if (hit) {
                a.triggered = true;
                post(Notification.Level.WARNING, "Price alert", a.productId + " is " + Fmt.coins(price)
                        + " (" + a.direction.toLowerCase(Locale.ROOT) + " " + Fmt.coins(a.price) + ")");
            }
        }
    }

    /** Alerts about exceptional opportunities, rate-limited per item to once per hour. */
    public void checkOpportunities(List<? extends Opportunity> opps) {
        SkyTradeConfig.Notifications n = config.get().notifications;
        if (!n.opportunityAlerts) return;
        long now = time.now();
        int sent = 0;
        for (Opportunity o : opps) {
            if (sent >= 2) break;
            if (o.adjustedProfitPerHour() < n.opportunityAlertMinProfitPerHour || o.quantity() <= 0) continue;
            Long last = lastAlert.get(o.key());
            if (last != null && now - last < 3_600_000L) continue;
            lastAlert.put(o.key(), now);
            sent++;
            post(Notification.Level.SUCCESS, "Opportunity", o.name() + ": " + Fmt.coins(o.adjustedProfitPerHour()) + "/h risk-adjusted, "
                    + Fmt.signedCoins(o.netProfit()) + " net  " + o.navigationCommand());
        }
        if (lastAlert.size() > 2000) lastAlert.clear();
    }
}
