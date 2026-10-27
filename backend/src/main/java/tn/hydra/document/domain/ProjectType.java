package tn.hydra.document.domain;

/**
 * Nature of the project — the real natures of Tunisian water/hydraulics works.
 *
 * <p>These values exist both in the Flyway/demo seeds and in live databases.
 * The original generic set (RESERVOIR/RESEAU/BRANCHEMENTS/MIXTE/AUTRE) is kept
 * for backward compatibility; the domain set matches what site teams actually
 * build: adduction mains, irrigation perimeters, valve chambers, boreholes,
 * pumping stations, sewerage, network rehabilitation and retention basins.</p>
 *
 * <p>Reads go through {@link ProjectTypeConverter} so an unknown legacy value
 * degrades to AUTRE (with a WARN log) instead of failing the whole JPA query
 * with "No enum constant" → HTTP 500 on every dossier/project screen.</p>
 */
public enum ProjectType {
    // generic (kept for backward compatibility)
    RESERVOIR, RESEAU, BRANCHEMENTS, MIXTE, AUTRE,
    // domain-specific (hydraulics vertical)
    ADDUCTION, IRRIGATION, VANNE, FORAGE, POMPAGE,
    ASSAINISSEMENT, REHABILITATION, BASSIN
}
