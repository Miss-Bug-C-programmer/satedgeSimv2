package edu.weijunyong.satedgesim.Topology;

import java.util.List;

/** Current and next deterministic contact summary for one ordered pair. */
public final class ContactForecast {
    public final boolean availableNow;
    public final double remainingLifetimeSec;
    public final boolean remainingLifetimeCensored;
    public final Double currentContactEndSec;
    public final Double nextContactStartSec;
    public final Double nextContactEndSec;
    public final List<ContactWindow> windows;
    public final double forecastStartSec;
    public final double forecastEndSec;
    public final double effectiveHorizonSec;
    public final String source;

    public ContactForecast(boolean availableNow, double remainingLifetimeSec,
            boolean remainingLifetimeCensored, Double currentContactEndSec,
            Double nextContactStartSec, Double nextContactEndSec,
            List<ContactWindow> windows, double forecastStartSec,
            double forecastEndSec, double effectiveHorizonSec, String source) {
        this.availableNow = availableNow;
        this.remainingLifetimeSec = remainingLifetimeSec;
        this.remainingLifetimeCensored = remainingLifetimeCensored;
        this.currentContactEndSec = currentContactEndSec;
        this.nextContactStartSec = nextContactStartSec;
        this.nextContactEndSec = nextContactEndSec;
        this.windows = windows;
        this.forecastStartSec = forecastStartSec;
        this.forecastEndSec = forecastEndSec;
        this.effectiveHorizonSec = effectiveHorizonSec;
        this.source = source;
    }
}
