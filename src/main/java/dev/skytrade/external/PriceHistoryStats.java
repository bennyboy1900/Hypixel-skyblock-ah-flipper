package dev.skytrade.external;

/**
 * Summary of an item's recent auction price history from an external price-history API.
 *
 * @param median    volume-weighted median of the bucket averages over the whole window
 * @param recent    volume-weighted average of the buckets in the last 24 hours (or the last bucket)
 * @param low       10th percentile of the bucket minimums (what the item reliably sells at, worst case)
 * @param high      90th percentile of the bucket maximums (what the best variants sell at)
 * @param volume    total sales in the window
 * @param trend     relative change from the first third of the window to the last third (-0.2 = 20% down)
 * @param buckets   number of history buckets the summary is built from
 * @param fetchedAt local time the data was fetched
 */
public record PriceHistoryStats(String key, double median, double recent, double low, double high, long volume,
                                double trend, int buckets, long fetchedAt) {
    public boolean usable() {
        return buckets >= 3 && median > 0;
    }
}
