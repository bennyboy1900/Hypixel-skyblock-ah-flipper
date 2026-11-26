package dev.skytrade.crafting;

import java.util.Map;

/** A verified crafting recipe. {@code source} records where it came from (never invented). */
public record Recipe(String outputId, int outputCount, Map<String, Integer> inputs, String source) {}
