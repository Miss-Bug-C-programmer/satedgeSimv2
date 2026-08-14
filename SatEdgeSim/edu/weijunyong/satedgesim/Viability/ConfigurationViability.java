package edu.weijunyong.satedgesim.Viability;

/**
 * Pure current-configuration viability evaluation.
 *
 * This class deliberately does not select an action, persist a configuration,
 * trigger KEEP/REPLAN, or inspect future stochastic state. It only compares
 * the deterministic current contact lifetime with the estimated completion
 * time of one candidate configuration.
 */
public final class ConfigurationViability {
    public enum Status {
        VIABLE,
        INVIABLE,
        UNCERTAIN
    }

    public static final class Report {
        public final Status status;
        public final String reason;
        public final String source;
        public final boolean evaluated;
        public final boolean contactEndCensored;
        public final double availableContactSec;
        public final double requiredContactSec;
        public final double serviceMarginSec;

        private Report(Status status, String reason, String source, boolean evaluated,
                boolean contactEndCensored, double availableContactSec,
                double requiredContactSec, double serviceMarginSec) {
            this.status = status;
            this.reason = reason;
            this.source = source;
            this.evaluated = evaluated;
            this.contactEndCensored = contactEndCensored;
            this.availableContactSec = availableContactSec;
            this.requiredContactSec = requiredContactSec;
            this.serviceMarginSec = serviceMarginSec;
        }

        public boolean isViable() {
            return status == Status.VIABLE;
        }

        public boolean isInviable() {
            return status == Status.INVIABLE;
        }

        public boolean isUncertain() {
            return status == Status.UNCERTAIN;
        }
    }

    private ConfigurationViability() {
    }

    /**
     * Evaluates one candidate using only current deterministic contact data.
     * A censored contact that already has enough lower-bound lifetime is marked
     * UNCERTAIN, not VIABLE, because its exact contact end is not known.
     */
    public static Report evaluate(boolean localConfiguration, boolean linkAvailableNow,
            double remainingContactSec, double estimatedCompletionSec,
            double requiredMarginSec, boolean contactEndCensored, String source) {
        String normalizedSource = source == null || source.trim().isEmpty()
                ? "unavailable" : source;
        double available = finiteNonNegative(remainingContactSec);
        double completion = finiteNonNegative(estimatedCompletionSec);
        double margin = finiteNonNegative(requiredMarginSec);
        double required = completion + margin;
        double serviceMargin = available - required;

        if (localConfiguration) {
            double localCapacity = 1.0e12;
            return new Report(Status.VIABLE, "local_configuration", "local", true,
                    false, localCapacity, 0.0, localCapacity);
        }
        if (!linkAvailableNow) {
            return new Report(Status.INVIABLE, "no_current_contact", normalizedSource, true,
                    contactEndCensored, available, required, serviceMargin);
        }
        if (serviceMargin < 0.0) {
            return new Report(Status.INVIABLE, "insufficient_contact_margin", normalizedSource, true,
                    contactEndCensored, available, required, serviceMargin);
        }
        if (contactEndCensored) {
            return new Report(Status.UNCERTAIN, "contact_end_censored", normalizedSource, true,
                    true, available, required, serviceMargin);
        }
        return new Report(Status.VIABLE, "contact_margin_sufficient", normalizedSource, true,
                false, available, required, serviceMargin);
    }

    private static double finiteNonNegative(double value) {
        if (Double.isNaN(value) || value < 0.0) return 0.0;
        if (Double.isInfinite(value)) return 1.0e12;
        return value;
    }
}
