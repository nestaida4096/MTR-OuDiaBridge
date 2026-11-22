package nest.oudiabridge.mtr4;

import nest.oudiabridge.parser.OudiaParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Matches an MTR4 vehicle against a train in an imported OuDia timetable.
 *
 * Matching is intentionally conservative: the route name must match, and the vehicle's
 * current/next station pair must identify exactly one train. Ambiguous matches are rejected.
 */
public final class OudiaVehicleMatcher {
    private OudiaVehicleMatcher() {
    }

    public static MatchResult match(OudiaParser.ParseResult timetable,
                                    Mtr4RuntimeAccess.VehicleSnapshot vehicle) {
        if (timetable == null || vehicle == null) {
            return MatchResult.noMatch("Timetable or vehicle is missing");
        }
        if (!same(timetable.routeName, vehicle.route())) {
            return MatchResult.noMatch("MTR route name does not match the OuDia route name");
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int trainIndex = 0; trainIndex < timetable.trains.size(); trainIndex++) {
            OudiaParser.TrainData train = timetable.trains.get(trainIndex);
            int score = scoreTrain(timetable, train, vehicle);
            if (score > 0) {
                candidates.add(new Candidate(trainIndex, score));
            }
        }

        if (candidates.isEmpty()) {
            return MatchResult.noMatch("No train contains the current/next station sequence");
        }

        int bestScore = candidates.stream().mapToInt(Candidate::score).max().orElse(0);
        // Do not persist an automatic binding based on a single station. A unique
        // current-only or next-only match is not enough to distinguish repeated services.
        if (bestScore < 3) {
            return MatchResult.noMatch("Insufficient evidence: both current and next stations must match consecutively");
        }
        List<Candidate> best = candidates.stream().filter(candidate -> candidate.score == bestScore).toList();
        if (best.size() != 1) {
            return MatchResult.ambiguous(best.size(), bestScore);
        }
        Candidate candidate = best.get(0);
        return MatchResult.matched(candidate.trainIndex, candidate.score);
    }

    /** Returns read-only diagnostics without creating or changing a vehicle binding. */
    public static Diagnostic diagnose(OudiaParser.ParseResult timetable,
                                      Mtr4RuntimeAccess.VehicleSnapshot vehicle) {
        if (timetable == null || vehicle == null) {
            return new Diagnostic(false, 0, 0, 0, 0, List.of(), java.util.Map.of(),
                    "", "", "Timetable or vehicle is missing");
        }

        String current = normalize(vehicle.station());
        String next = normalize(vehicle.nextStation());
        boolean routeMatches = same(timetable.routeName, vehicle.route());
        int currentOnly = 0;
        int nextOnly = 0;
        int pairMatches = 0;
        java.util.Map<String, Integer> directions = new java.util.LinkedHashMap<>();
        List<Integer> pairTrainIndices = new ArrayList<>();

        for (int trainIndex = 0; trainIndex < timetable.trains.size(); trainIndex++) {
            OudiaParser.TrainData train = timetable.trains.get(trainIndex);
            directions.merge(train.direction == null || train.direction.isBlank()
                    ? "(empty)" : train.direction, 1, Integer::sum);
            List<String> sequence = new ArrayList<>();
            for (OudiaParser.StopTime stop : train.stops) {
                if (stop.stopType == OudiaParser.StopType.NONE
                        || stop.stationIndex < 0 || stop.stationIndex >= timetable.stations.size()) {
                    continue;
                }
                String name = mappedStationName(timetable, stop.stationIndex);
                if (!name.isEmpty()) sequence.add(name);
            }

            boolean hasCurrent = !current.isEmpty() && sequence.contains(current);
            boolean hasNext = !next.isEmpty() && sequence.contains(next);
            boolean hasPair = false;
            for (int i = 0; i + 1 < sequence.size(); i++) {
                if (!current.isEmpty() && !next.isEmpty()
                        && same(sequence.get(i), current)
                        && same(sequence.get(i + 1), next)) {
                    hasPair = true;
                    break;
                }
            }
            if (hasPair) {
                pairMatches++;
                if (pairTrainIndices.size() < 12) pairTrainIndices.add(trainIndex);
            } else if (hasCurrent) {
                currentOnly++;
            } else if (hasNext) {
                nextOnly++;
            }
        }

        String reason = !routeMatches
                ? "Route name differs; station-pair counts are shown independently"
                : pairMatches == 0
                ? "No train contains the current/next station pair consecutively"
                : pairMatches == 1
                ? "Exactly one train contains the consecutive station pair"
                : "Multiple trains contain the consecutive station pair";
        return new Diagnostic(routeMatches, timetable.trains.size(), currentOnly, nextOnly,
                pairMatches, List.copyOf(pairTrainIndices), java.util.Map.copyOf(directions),
                normalize(timetable.routeName), normalize(vehicle.route()), reason);
    }

    public record Diagnostic(boolean routeMatches, int trainCount, int currentOnlyCount,
                             int nextOnlyCount, int pairMatchCount, List<Integer> pairTrainIndices,
                             java.util.Map<String, Integer> directionCounts,
                             String normalizedTimetableRoute, String normalizedVehicleRoute,
                             String reason) {
    }

    private static int scoreTrain(OudiaParser.ParseResult timetable,
                                  OudiaParser.TrainData train,
                                  Mtr4RuntimeAccess.VehicleSnapshot vehicle) {
        String current = normalize(vehicle.station());
        String next = normalize(vehicle.nextStation());
        if (current.isEmpty() && next.isEmpty()) {
            return 0;
        }

        List<String> sequence = new ArrayList<>();
        for (OudiaParser.StopTime stop : train.stops) {
            if (stop.stopType == OudiaParser.StopType.NONE) {
                continue;
            }
            if (stop.stationIndex < 0 || stop.stationIndex >= timetable.stations.size()) {
                continue;
            }
            String name = mappedStationName(timetable, stop.stationIndex);
            if (!name.isEmpty()) {
                sequence.add(name);
            }
        }

        int score = 0;
        for (int i = 0; i < sequence.size(); i++) {
            boolean currentMatches = !current.isEmpty() && same(sequence.get(i), current);
            if (currentMatches) {
                score = Math.max(score, 1);
                if (i + 1 < sequence.size() && !next.isEmpty() && same(sequence.get(i + 1), next)) {
                    score = 3;
                    break;
                }
            }
            if (current.isEmpty() && !next.isEmpty() && same(sequence.get(i), next)) {
                score = Math.max(score, 2);
            }
        }
        return score;
    }

    private static String mappedStationName(OudiaParser.ParseResult timetable, int index) {
        if (index < timetable.syncConfig.stationMappings.size()) {
            String mapped = timetable.syncConfig.stationMappings.get(index).mtrStationName;
            if (mapped != null && !mapped.isBlank()) {
                return normalize(mapped);
            }
        }
        return normalize(timetable.stations.get(index).name);
    }

    private static boolean same(String first, String second) {
        return normalize(first).equals(normalize(second)) && !normalize(first).isEmpty();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        // MTR4 names may contain "Japanese|English||id" metadata.
        // OuDia normally stores the Japanese station/route name only.
        String primaryName = value.split("\\|", 2)[0];
        return primaryName.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private record Candidate(int trainIndex, int score) {
    }

    public record MatchResult(Status status, int trainIndex, int candidateCount, int score, String reason) {
        public static MatchResult matched(int trainIndex, int score) {
            return new MatchResult(Status.MATCHED, trainIndex, 1, score, "");
        }

        public static MatchResult ambiguous(int count, int score) {
            return new MatchResult(Status.AMBIGUOUS, -1, count, score, "More than one train has the same best station match");
        }

        public static MatchResult noMatch(String reason) {
            return new MatchResult(Status.NO_MATCH, -1, 0, 0, reason);
        }
    }

    public enum Status {
        MATCHED,
        AMBIGUOUS,
        NO_MATCH
    }
}
