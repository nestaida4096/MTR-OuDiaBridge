package nest.oudiabridge.mtr4;

import nest.oudiabridge.parser.OudiaParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only ranking of OuDia trains that serve an MTR4 vehicle's current station pair.
 *
 * The clock is derived from Simulator.getCurrentMillis(), which is an absolute simulation
 * timestamp. It is deliberately not derived from Minecraft's daylight-cycle clock.
 */
public final class OudiaTimeMatcher {
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private OudiaTimeMatcher() {
    }

    public static Result analyze(OudiaParser.ParseResult timetable,
                                 Mtr4RuntimeAccess.VehicleSnapshot vehicle,
                                 ZoneId zoneId) {
        if (timetable == null || vehicle == null) {
            return Result.failure("", "Timetable or vehicle is unavailable");
        }
        if (vehicle.simulationMillis() <= 0) {
            return Result.failure("", "MTR4 simulation clock is unavailable");
        }

        String clockText = Instant.ofEpochMilli(vehicle.simulationMillis())
                .atZone(zoneId).format(CLOCK_FORMAT);
        int clockSeconds = OudiaScheduleAnalyzer.parseTime(clockText);

        if (!same(timetable.routeName, vehicle.route())) {
            return Result.failure(clockText, "Route name does not match");
        }

        String currentName = normalize(vehicle.station());
        String nextName = normalize(vehicle.nextStation());
        if (currentName.isEmpty() || nextName.isEmpty()) {
            return Result.failure(clockText, "MTR4 did not provide both current and next station names");
        }

        List<Candidate> candidates = new ArrayList<>();
        int pairMatchesWithoutTimes = 0;

        for (int trainIndex = 0; trainIndex < timetable.trains.size(); trainIndex++) {
            OudiaParser.TrainData train = timetable.trains.get(trainIndex);
            List<TimedStop> stops = buildStops(timetable, train);

            for (int i = 0; i + 1 < stops.size(); i++) {
                TimedStop current = stops.get(i);
                TimedStop next = stops.get(i + 1);
                if (!same(current.stationName(), currentName)
                        || !same(next.stationName(), nextName)) {
                    continue;
                }

                Integer departure = current.departureSeconds() != null
                        ? current.departureSeconds() : current.arrivalSeconds();
                Integer arrival = next.arrivalSeconds() != null
                        ? next.arrivalSeconds() : next.departureSeconds();
                if (departure == null || arrival == null) {
                    pairMatchesWithoutTimes++;
                    continue;
                }

                while (arrival < departure) {
                    arrival += SECONDS_PER_DAY;
                }

                int alignedClock = alignClock(clockSeconds, departure);
                int departureDelta = alignedClock - departure;
                int intervalDistance = alignedClock < departure
                        ? departure - alignedClock
                        : Math.max(0, alignedClock - arrival);

                candidates.add(new Candidate(trainIndex,
                        train.direction == null || train.direction.isBlank() ? "(empty)" : train.direction,
                        departure, arrival, departureDelta, intervalDistance,
                        intervalDistance == 0));
            }
        }

        candidates.sort(Comparator
                .comparingInt(Candidate::intervalDistanceSeconds)
                .thenComparingInt(candidate -> Math.abs(candidate.departureDeltaSeconds()))
                .thenComparingInt(Candidate::trainIndex));

        String reason = candidates.isEmpty()
                ? pairMatchesWithoutTimes > 0
                    ? "Station pair matched, but its schedule times are missing or invalid"
                    : "No train contains the current/next station pair"
                : "Candidates ranked by distance from the scheduled current-to-next-station time window";
        return new Result(true, clockText, candidates.size(), pairMatchesWithoutTimes,
                List.copyOf(candidates.subList(0, Math.min(12, candidates.size()))), reason);
    }

    private static List<TimedStop> buildStops(OudiaParser.ParseResult timetable,
                                               OudiaParser.TrainData train) {
        List<TimedStop> result = new ArrayList<>();
        int previousTime = -1;
        for (OudiaParser.StopTime stop : train.stops) {
            if (stop.stopType == OudiaParser.StopType.NONE
                    || stop.stationIndex < 0 || stop.stationIndex >= timetable.stations.size()) {
                continue;
            }

            Integer arrival = parseOptional(stop.arrivalTime);
            Integer departure = parseOptional(stop.departureTime);
            if (arrival != null) {
                arrival = advanceDay(arrival, previousTime);
                previousTime = arrival;
            }
            if (departure != null) {
                departure = advanceDay(departure, previousTime);
                previousTime = departure;
            }

            result.add(new TimedStop(stationName(timetable, stop.stationIndex),
                    arrival, departure));
        }
        return result;
    }

    private static Integer parseOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OudiaScheduleAnalyzer.parseTime(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int advanceDay(int time, int previous) {
        while (previous >= 0 && time < previous) {
            time += SECONDS_PER_DAY;
        }
        return time;
    }

    private static int alignClock(int clockSeconds, int scheduleSeconds) {
        int best = clockSeconds;
        int bestDifference = Math.abs(clockSeconds - scheduleSeconds);
        for (int day = -2; day <= 2; day++) {
            int candidate = clockSeconds + day * SECONDS_PER_DAY;
            int difference = Math.abs(candidate - scheduleSeconds);
            if (difference < bestDifference) {
                best = candidate;
                bestDifference = difference;
            }
        }
        return best;
    }

    private static String stationName(OudiaParser.ParseResult timetable, int index) {
        if (index < timetable.syncConfig.stationMappings.size()) {
            String mapped = timetable.syncConfig.stationMappings.get(index).mtrStationName;
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        }
        return timetable.stations.get(index).name;
    }

    private static boolean same(String first, String second) {
        String normalizedFirst = normalize(first);
        return !normalizedFirst.isEmpty() && normalizedFirst.equals(normalize(second));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.split("\\|", 2)[0].trim()
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String formatTime(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                safeSeconds / 3600, safeSeconds % 3600 / 60, safeSeconds % 60);
    }

    public record Candidate(int trainIndex, String direction,
                            int scheduledDepartureSeconds, int scheduledArrivalSeconds,
                            int departureDeltaSeconds, int intervalDistanceSeconds,
                            boolean insideScheduledInterval) {
        public String scheduledDepartureText() {
            return formatTime(scheduledDepartureSeconds);
        }

        public String scheduledArrivalText() {
            return formatTime(scheduledArrivalSeconds);
        }
    }

    public record Result(boolean available, String clockText, int candidateCount,
                         int pairMatchesWithoutTimes, List<Candidate> topCandidates,
                         String reason) {
        private static Result failure(String clockText, String reason) {
            return new Result(false, clockText, 0, 0, List.of(), reason);
        }
    }

    private record TimedStop(String stationName, Integer arrivalSeconds,
                             Integer departureSeconds) {
    }
}
