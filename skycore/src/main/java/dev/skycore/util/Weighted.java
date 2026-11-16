package dev.skycore.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Immutable weighted random table. */
public final class Weighted<T> {
    private final List<T> values = new ArrayList<>();
    private final double[] cumulative;
    private final double total;

    public Weighted(Map<T, Double> weights) {
        cumulative = new double[weights.size()];
        double sum = 0;
        int i = 0;
        for (Map.Entry<T, Double> e : weights.entrySet()) {
            if (e.getValue() <= 0) continue;
            sum += e.getValue();
            values.add(e.getKey());
            cumulative[i++] = sum;
        }
        total = sum;
    }

    public T pick() {
        if (values.isEmpty()) return null;
        double r = ThreadLocalRandom.current().nextDouble(total);
        for (int i = 0; i < values.size(); i++) if (r < cumulative[i]) return values.get(i);
        return values.getLast();
    }

    public boolean contains(T v) {
        return values.contains(v);
    }

    public List<T> values() {
        return values;
    }
}
