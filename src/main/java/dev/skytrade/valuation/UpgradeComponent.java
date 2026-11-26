package dev.skytrade.valuation;

/** One purchasable upgrade applied to an item (e.g. 10x HOT_POTATO_BOOK). */
public record UpgradeComponent(String itemId, int quantity, String label) {
    public String encode() {
        return itemId + "x" + quantity;
    }

    public static UpgradeComponent decode(String s) {
        int x = s.lastIndexOf('x');
        if (x <= 0) return new UpgradeComponent(s, 1, s);
        try {
            return new UpgradeComponent(s.substring(0, x), Integer.parseInt(s.substring(x + 1)), s.substring(0, x));
        } catch (NumberFormatException e) {
            return new UpgradeComponent(s, 1, s);
        }
    }
}
