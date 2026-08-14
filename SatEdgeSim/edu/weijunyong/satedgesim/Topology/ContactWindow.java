package edu.weijunyong.satedgesim.Topology;

/** One deterministic interval in which an ordered link is available. */
public final class ContactWindow {
    public final double startSec;
    public final double endSec;
    public final double durationSec;
    public final TopologyNodeRef source;
    public final TopologyNodeRef destination;
    public final boolean startsInsideQuery;
    public final boolean endsInsideQuery;
    public final boolean leftCensored;
    public final boolean rightCensored;

    public ContactWindow(double startSec, double endSec, TopologyNodeRef source,
            TopologyNodeRef destination, boolean startsInsideQuery, boolean endsInsideQuery,
            boolean leftCensored, boolean rightCensored) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("contact window endpoints must not be null");
        }
        if (Double.isNaN(startSec) || Double.isNaN(endSec) || startSec < 0.0) {
            throw new IllegalArgumentException("contact window times must be finite and non-negative");
        }
        this.startSec = startSec;
        this.endSec = Math.max(startSec, endSec);
        this.durationSec = this.endSec - startSec;
        this.source = source;
        this.destination = destination;
        this.startsInsideQuery = startsInsideQuery;
        this.endsInsideQuery = endsInsideQuery;
        this.leftCensored = leftCensored;
        this.rightCensored = rightCensored;
    }
}
