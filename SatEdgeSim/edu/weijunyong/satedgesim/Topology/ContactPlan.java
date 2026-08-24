package edu.weijunyong.satedgesim.Topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lazy deterministic contact-window cache. The cache contains only trajectory,
 * geometry and directional communication-range truth; it never stores load,
 * queue, task, channel or compute state.
 */
public final class ContactPlan {
    public static final String SOURCE = "deterministic_trajectory";
    public static final String LIFETIME_SOURCE = "deterministic_predictable_contact_plan";

    public static final class Stats {
        public long cacheHits;
        public long cacheMisses;
        public long pairsCached;
        public long contactWindowsGenerated;
        public long topologyQueries;

        public Stats copy() {
            Stats copy = new Stats();
            copy.cacheHits = cacheHits;
            copy.cacheMisses = cacheMisses;
            copy.pairsCached = pairsCached;
            copy.contactWindowsGenerated = contactWindowsGenerated;
            copy.topologyQueries = topologyQueries;
            return copy;
        }
    }

    private static final class PairWindows {
        final TopologyNodeRef source;
        final TopologyNodeRef destination;
        final List<ContactWindow> windows;

        PairWindows(TopologyNodeRef source, TopologyNodeRef destination, List<ContactWindow> windows) {
            this.source = source;
            this.destination = destination;
            this.windows = windows;
        }
    }

    private final TopologyOracle oracle;
    private final double configuredHorizonSec;
    private final double scanStepSec;
    private final double refineToleranceSec;
    private final Map<String, PairWindows> cache = new LinkedHashMap<String, PairWindows>();
    private final Stats stats = new Stats();

    public ContactPlan(TopologyOracle oracle) {
        this(oracle, 600.0, 1.0, 0.1);
    }

    public ContactPlan(TopologyOracle oracle, double configuredHorizonSec,
            double scanStepSec, double refineToleranceSec) {
        if (oracle == null) throw new IllegalArgumentException("topology oracle must not be null");
        if (configuredHorizonSec <= 0.0 || scanStepSec <= 0.0 || refineToleranceSec <= 0.0) {
            throw new IllegalArgumentException("contact plan timing parameters must be positive");
        }
        this.oracle = oracle;
        this.configuredHorizonSec = configuredHorizonSec;
        this.scanStepSec = scanStepSec;
        this.refineToleranceSec = refineToleranceSec;
    }

    public List<ContactWindow> getContactWindows(TopologyNodeRef source, TopologyNodeRef destination,
            double fromTimeSec, double horizonSec) {
        ContactForecast forecast = getContactForecast(source, destination, fromTimeSec, horizonSec);
        return forecast.windows;
    }

    public double getRemainingContactLifetimeSec(TopologyNodeRef source, TopologyNodeRef destination,
            double nowSec, double horizonSec) {
        return getContactForecast(source, destination, nowSec, horizonSec).remainingLifetimeSec;
    }

    public ContactForecast getContactForecast(TopologyNodeRef source, TopologyNodeRef destination,
            double fromTimeSec, double horizonSec) {
        double start = oracle.clampTime(fromTimeSec);
        double requestedHorizon = Double.isNaN(horizonSec) || Double.isInfinite(horizonSec)
                ? configuredHorizonSec : Math.max(0.0, horizonSec);
        double effectiveHorizon = Math.min(configuredHorizonSec, requestedHorizon);
        effectiveHorizon = Math.min(effectiveHorizon, Math.max(0.0, oracle.getMaxAvailableTimeSec() - start));
        double end = start + effectiveHorizon;
        PairWindows pair = getOrBuild(source, destination);
        List<ContactWindow> clipped = clipWindows(pair.windows, start, end);
        boolean availableNow = false;
        Double currentEnd = null;
        Double nextStart = null;
        Double nextEnd = null;
        double remaining = 0.0;
        boolean censored = false;
        for (ContactWindow window : clipped) {
            if (window.startSec <= start + 1.0e-9 && window.endSec >= start - 1.0e-9) {
                availableNow = true;
                currentEnd = window.endSec;
                remaining = Math.max(0.0, window.endSec - start);
                censored = window.rightCensored;
                break;
            }
            if (window.startSec > start + 1.0e-9) {
                nextStart = window.startSec;
                nextEnd = window.endSec;
                break;
            }
        }
        return new ContactForecast(availableNow, remaining, censored, currentEnd,
                nextStart, nextEnd, Collections.unmodifiableList(clipped), start, end,
                effectiveHorizon, SOURCE);
    }

    public Stats getStats() {
        synchronized (cache) {
            return stats.copy();
        }
    }

    public double getConfiguredHorizonSec() {
        return configuredHorizonSec;
    }

    public double getScanStepSec() {
        return scanStepSec;
    }

    public double getRefineToleranceSec() {
        return refineToleranceSec;
    }

    private PairWindows getOrBuild(TopologyNodeRef source, TopologyNodeRef destination) {
        String key = source.toString() + "->" + destination.toString();
        synchronized (cache) {
            PairWindows cached = cache.get(key);
            if (cached != null) {
                stats.cacheHits++;
                return cached;
            }
            stats.cacheMisses++;
            List<ContactWindow> windows = scanPair(source, destination);
            PairWindows created = new PairWindows(source, destination, windows);
            cache.put(key, created);
            stats.pairsCached = cache.size();
            stats.contactWindowsGenerated += windows.size();
            return created;
        }
    }

    private List<ContactWindow> scanPair(TopologyNodeRef source, TopologyNodeRef destination) {
        final double traceEnd = oracle.getMaxAvailableTimeSec();
        List<ContactWindow> windows = new ArrayList<ContactWindow>();
        if (traceEnd <= 0.0) {
            if (isAvailable(source, destination, 0.0)) {
                windows.add(new ContactWindow(0.0, traceEnd, source, destination,
                        true, true, true, true));
            }
            return windows;
        }
        double cursor = 0.0;
        boolean previous = isAvailable(source, destination, cursor);
        stats.topologyQueries++;
        double activeStart = previous ? 0.0 : Double.NaN;
        while (cursor < traceEnd - 1.0e-12) {
            double next = Math.min(traceEnd, cursor + scanStepSec);
            boolean current = isAvailable(source, destination, next);
            stats.topologyQueries++;
            if (!previous && current) {
                activeStart = refineTransition(source, destination, cursor, next, false);
            } else if (previous && !current) {
                double activeEnd = refineTransition(source, destination, cursor, next, true);
                windows.add(new ContactWindow(activeStart, activeEnd, source, destination,
                        activeStart > 0.0, activeEnd < traceEnd, false, activeEnd >= traceEnd - refineToleranceSec));
                activeStart = Double.NaN;
            }
            cursor = next;
            previous = current;
        }
        if (previous) {
            windows.add(new ContactWindow(activeStart, traceEnd, source, destination,
                    activeStart > 0.0, false, false, true));
        }
        return windows;
    }

    private double refineTransition(TopologyNodeRef source, TopologyNodeRef destination,
            double left, double right, boolean trueToFalse) {
        double low = left;
        double high = right;
        while (high - low > refineToleranceSec) {
            double middle = (low + high) / 2.0;
            boolean available = isAvailable(source, destination, middle);
            stats.topologyQueries++;
            if (trueToFalse) {
                if (available) low = middle;
                else high = middle;
            } else {
                if (available) high = middle;
                else low = middle;
            }
        }
        return (low + high) / 2.0;
    }

    private boolean isAvailable(TopologyNodeRef source, TopologyNodeRef destination, double timeSec) {
        return oracle.getLinkSnapshot(source, destination, timeSec).available;
    }

    private List<ContactWindow> clipWindows(List<ContactWindow> sourceWindows, double start, double end) {
        List<ContactWindow> result = new ArrayList<ContactWindow>();
        for (ContactWindow original : sourceWindows) {
            if (original.endSec < start - 1.0e-9 || original.startSec > end + 1.0e-9) continue;
            double clippedStart = Math.max(start, original.startSec);
            double clippedEnd = Math.min(end, original.endSec);
            if (clippedEnd < clippedStart - 1.0e-9) continue;
            boolean leftCensored = original.startSec < start - 1.0e-9;
            boolean rightCensored = original.endSec > end - 1.0e-9 || original.rightCensored;
            result.add(new ContactWindow(clippedStart, clippedEnd, original.source,
                    original.destination, !leftCensored && original.startsInsideQuery,
                    !rightCensored && original.endsInsideQuery, leftCensored, rightCensored));
        }
        Collections.sort(result, new Comparator<ContactWindow>() {
            @Override
            public int compare(ContactWindow first, ContactWindow second) {
                return Double.compare(first.startSec, second.startSec);
            }
        });
        return result;
    }
}
