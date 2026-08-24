package edu.weijunyong.satedgesim.Viability;

/** Lightweight unit smoke test for viability semantics. */
public final class ConfigurationViabilitySmoke {
    private ConfigurationViabilitySmoke() {
    }

    public static void main(String[] args) {
        ConfigurationViability.Report local = ConfigurationViability.evaluate(
                true, true, 0.0, 10.0, 0.0, false, "local");
        require(local.isViable(), "local configuration must be viable");

        ConfigurationViability.Report unavailable = ConfigurationViability.evaluate(
                false, false, 100.0, 10.0, 0.0, false, "deterministic_predictable_contact_plan");
        require(unavailable.isInviable() && "no_current_contact".equals(unavailable.reason),
                "unavailable link must be inviable");

        ConfigurationViability.Report insufficient = ConfigurationViability.evaluate(
                false, true, 5.0, 10.0, 1.0, false, "deterministic_predictable_contact_plan");
        require(insufficient.isInviable(), "short contact must be inviable");

        ConfigurationViability.Report censored = ConfigurationViability.evaluate(
                false, true, 100.0, 10.0, 1.0, true, "deterministic_predictable_contact_plan");
        require(censored.isUncertain() && censored.contactEndCensored,
                "censored sufficient contact must be uncertain");

        ConfigurationViability.Report sufficient = ConfigurationViability.evaluate(
                false, true, 100.0, 10.0, 1.0, false, "deterministic_predictable_contact_plan");
        require(sufficient.isViable() && sufficient.serviceMarginSec >= 89.0,
                "uncensored sufficient contact must be viable");
        System.out.println("ConfigurationViability smoke: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
