package org.opentripplanner.routing.trippattern;

import java.io.Serializable;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.opentripplanner.model.StopTime;
import org.opentripplanner.model.Trip;
import org.opentripplanner.common.MavenVersion;
import org.opentripplanner.gtfs.BikeAccess;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.ServiceDay;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.request.BannedStopSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;

/**
 * A TripTimes represents the arrival and departure times for a single trip in an Timetable. It is carried
 * along by States when routing to ensure that they have a consistent, fast view of the trip when
 * realtime updates have been applied. All times are expressed as seconds since midnight (as in GTFS).
 */
public class TripTimes implements Serializable, Comparable<TripTimes>, Cloneable {

    private static final long serialVersionUID = MavenVersion.VERSION.getUID();
    private static final Logger LOG = LoggerFactory.getLogger(TripTimes.class);

    /**
     * This constant is used for indicating passed stops, fully canceled trips and trips that are
     * otherwise unavailable during routing. It should only be used in a contiguous block at the
     * beginning of the trip and may or may not cover the entire trip. Partially canceling a trip in
     * this way is specifically not allowed.
     */
    public static final int UNAVAILABLE = -1;

    /**
     * This allows re-using the same scheduled arrival and departure time arrays for many
     * different TripTimes. It is also used in materializing frequency-based TripTimes.
     */
    int timeShift;

    /** The trips whose arrivals and departures are represented by this TripTimes */
    public final Trip trip;

    /** The code for the service on which this trip runs. For departure search optimizations. */
    // not final because these are set later, after TripTimes construction.
    public int serviceCode = -1;

    /**
     * Both trip_headsign and stop_headsign (per stop on a particular trip) are optional GTFS
     * fields. If the headsigns array is null, we will report the trip_headsign (which may also
     * be null) at every stop on the trip. If all the stop_headsigns are the same as the
     * trip_headsign we may also set the headsigns array to null to save space.
     * Field is private to force use of the getter method which does the necessary fallbacks.
     */
    private final String[] headsigns;

    /**
     * The time in seconds after midnight at which the vehicle should arrive at each stop according
     * to the original schedule.
     */
    final int[] scheduledArrivalTimes;

    /**
     * The time in seconds after midnight at which the vehicle should leave each stop according
     * to the original schedule.
     */
    final int[] scheduledDepartureTimes;

    /**
     * The time in seconds after midnight at which the vehicle arrives at each stop, accounting for
     * any real-time updates. Non-final to allow updates.
     */
    int[] arrivalTimes;

    /**
     * The time in seconds after midnight at which the vehicle leaves each stop, accounting for
     * any real-time updates. Non-final to allow updates.
     */
    int[] departureTimes;

    BitSet canceledArrivalTimes;

    BitSet canceledDepartureTimes;

    /**
     * These are the GTFS stop sequence numbers, which show the order in which the vehicle visits
     * the stops. Despite the face that the StopPattern or TripPattern enclosing this TripTimes
     * provides an ordered list of Stops, the original stop sequence numbers may still be needed for
     * matching with GTFS-RT update messages. Unfortunately, each individual trip can have totally
     * different sequence numbers for the same stops, so we need to store them at the individual
     * trip level. An effort is made to re-use the sequence number arrays when they are the same
     * across different trips in the same pattern.
     */
    private final int[] stopSequences;

    private final int[] continuousPickup;

    private final int[] continuousDropOff;

    private final double[] serviceAreaRadius;

    private final String[] serviceArea;

    /**
     * The real-time state of this TripTimes.
     */
    private RealTimeState realTimeState = RealTimeState.SCHEDULED;

    /** A Set of stop indexes that are marked as timepoints in the GTFS input. */
    private final BitSet timepoints;

    /**
     * Demand-Response Transit (DRT) service parameters. In GTFS-Flex, DRT transit is transit which
     * is reserved in advance with the provider. This includes call-and-ride service, and
     * potentially the deviated portion of deviated-fixed routes. For DRT, the travel time is
     * calculated by applying formulas to the direct vehicle time. The following parameters give
     * the maximum possible and average travel time formulas, given a direct vehicle time.
     */

    private DrtTravelTime maxTravelTime;

    private DrtTravelTime avgTravelTime;

    private double advanceBookMin = 0;

    /**
     * The provided stopTimes are assumed to be pre-filtered, valid, and monotonically increasing.
     * The non-interpolated stoptimes should already be marked at timepoints by a previous filtering step.
     */
    public TripTimes(final Trip trip, final List<StopTime> stopTimes, final Deduplicator deduplicator) {
        this.trip = trip;
        final int nStops = stopTimes.size();
        final int[] departures = new int[nStops];
        final int[] arrivals   = new int[nStops];
        final int[] sequences  = new int[nStops];
        final int[] continuousPickup = new int[nStops];
        final int[] continuousDropOff = new int[nStops];
        final double[] serviceAreaRadius = new double[nStops];
        final String[] serviceArea = new String[nStops];
        final BitSet timepoints = new BitSet(nStops);

        canceledArrivalTimes = new BitSet(nStops);
        canceledDepartureTimes = new BitSet(nStops);

        // Times are always shifted to zero. This is essential for frequencies and deduplication.
        timeShift = stopTimes.get(0).getArrivalTime();
        double radius = 0;
        String area = null;
        int s = 0;
        for (final StopTime st : stopTimes) {
            departures[s] = st.getDepartureTime() - timeShift;
            arrivals[s] = st.getArrivalTime() - timeShift;
            sequences[s] = st.getStopSequence();
            timepoints.set(s, st.getTimepoint() == 1);
            continuousPickup[s] = st.getContinuousPickup();
            continuousDropOff[s] = st.getContinuousDropOff();

            if (st.getStartServiceAreaRadius() != StopTime.MISSING_VALUE) {
                radius = st.getStartServiceAreaRadius();
            }
            serviceAreaRadius[s] = radius;
            if (st.getEndServiceAreaRadius() != StopTime.MISSING_VALUE) {
                if (st.getEndServiceAreaRadius() != radius) {
                    String message = String.format("Trip %s: start service area radius %g does not match end radius %g",
                            st.getTrip().getId(), radius, st.getEndServiceAreaRadius());
                    throw new IllegalArgumentException(message);
                }
                radius = 0;
            }

            if (st.getStartServiceArea() != null) {
                area = st.getStartServiceArea().getAreaId();
            }
            serviceArea[s] = area;
            if (st.getEndServiceArea() != null) {
                if (!st.getEndServiceArea().getAreaId().equals(area)) {
                    String message = String.format("Trip %s: start service area %s does not match end area %s",
                            st.getTrip().getId(), area, st.getEndServiceArea());
                    throw new IllegalArgumentException(message);
                }
                area = null;
            }

            s++;
        }
        this.scheduledDepartureTimes = deduplicator.deduplicateIntArray(departures);
        this.scheduledArrivalTimes = deduplicator.deduplicateIntArray(arrivals);
        this.stopSequences = deduplicator.deduplicateIntArray(sequences);
        this.headsigns = deduplicator.deduplicateStringArray(makeHeadsignsArray(stopTimes));
        // We set these to null to indicate that this is a non-updated/scheduled TripTimes.
        // We cannot point to the scheduled times because they are shifted, and updated times are not.
        this.arrivalTimes = null;
        this.departureTimes = null;
        this.timepoints = deduplicator.deduplicateBitSet(timepoints);
        this.continuousPickup = deduplicator.deduplicateIntArray(continuousPickup);
        this.continuousDropOff = deduplicator.deduplicateIntArray(continuousDropOff);
        this.serviceAreaRadius = deduplicator.deduplicateDoubleArray(serviceAreaRadius);
        this.serviceArea = deduplicator.deduplicateStringArray(serviceArea);
        if (trip.getDrtMaxTravelTime() != null) {
            this.maxTravelTime = DrtTravelTime.fromSpec(trip.getDrtMaxTravelTime());
        }
        if (trip.getDrtAvgTravelTime() != null) {
            this.avgTravelTime = DrtTravelTime.fromSpec(trip.getDrtAvgTravelTime());
        }
        this.advanceBookMin = trip.getDrtAdvanceBookMin();
        LOG.trace("trip {} has timepoint at indexes {}", trip, timepoints);
    }

    /** This copy constructor does not copy the actual times, only the scheduled times. */
    // It might be more maintainable to clone the triptimes then null out the scheduled times.
    // However, we then lose the "final" modifiers on the fields, and the immutability.
    public TripTimes(final TripTimes object) {
        this.trip = object.trip;
        this.serviceCode = object.serviceCode;
        this.timeShift = object.timeShift;
        this.headsigns = object.headsigns;
        this.scheduledDepartureTimes = object.scheduledDepartureTimes;
        this.scheduledArrivalTimes = object.scheduledArrivalTimes;
        this.stopSequences = object.stopSequences;
        this.timepoints = object.timepoints;
        canceledArrivalTimes = new BitSet(object.scheduledDepartureTimes.length);
        canceledDepartureTimes = new BitSet(object.scheduledDepartureTimes.length);
        this.continuousPickup = object.continuousPickup;
        this.continuousDropOff = object.continuousDropOff;
        this.serviceAreaRadius = object.serviceAreaRadius;
        this.serviceArea = object.serviceArea;
        this.maxTravelTime = object.maxTravelTime;
        this.avgTravelTime = object.avgTravelTime;
        this.advanceBookMin = object.advanceBookMin;
    }

    /**
     * @return either an array of headsigns (one for each stop on this trip) or null if the
     * headsign is the same at all stops (including null) and can be found in the Trip object.
     */
    private String[] makeHeadsignsArray(final List<StopTime> stopTimes) {
        final String tripHeadsign = trip.getTripHeadsign();
        boolean useStopHeadsigns = false;
        if (tripHeadsign == null) {
            useStopHeadsigns = true;
        } else {
            for (final StopTime st : stopTimes) {
                if ( ! (tripHeadsign.equals(st.getStopHeadsign()))) {
                    useStopHeadsigns = true;
                    break;
                }
            }
        }
        if (!useStopHeadsigns) {
            return null; //defer to trip_headsign
        }
        boolean allNull = true;
        int i = 0;
        final String[] hs = new String[stopTimes.size()];
        for (final StopTime st : stopTimes) {
            final String headsign = st.getStopHeadsign();
            hs[i++] = headsign;
            if (headsign != null) allNull = false;
        }
        if (allNull) {
            return null;
        } else {
            return hs;
        }
    }

    /**
     * Trips may also have null headsigns, in which case we should fall back on a Timetable or
     * Pattern-level headsign. Such a string will be available when we give TripPatterns or
     * StopPatterns unique human readable route variant names, but a TripTimes currently does not
     * have a pointer to its enclosing timetable or pattern.
     */
    public String getHeadsign(final int stop) {
        if (headsigns == null) {
            return trip.getTripHeadsign();
        } else {
            return headsigns[stop];
        }
    }

    /** @return the time in seconds after midnight that the vehicle arrives at the stop. */
    public int getScheduledArrivalTime(final int stop) {
        return scheduledArrivalTimes[stop] + timeShift;
    }

    /** @return the amount of time in seconds that the vehicle waits at the stop. */
    public int getScheduledDepartureTime(final int stop) {
        return scheduledDepartureTimes[stop] + timeShift;
    }

    /** @return the time in seconds after midnight that the vehicle arrives at the stop. */
    public int getArrivalTime(final int stop) {
        if (arrivalTimes == null) return getScheduledArrivalTime(stop);
        else return arrivalTimes[stop]; // updated times are not time shifted.
    }

    /** @return the amount of time in seconds that the vehicle waits at the stop. */
    public int getDepartureTime(final int stop) {
        if (departureTimes == null) return getScheduledDepartureTime(stop);
        else return departureTimes[stop]; // updated times are not time shifted.
    }

    /** @return the amount of time in seconds that the vehicle waits at the stop. */
    public int getDwellTime(final int stop) {
        // timeShift is not relevant since this involves updated times and is relative.
        return getDepartureTime(stop) - getArrivalTime(stop);
    }

    /** @return the amount of time in seconds that the vehicle takes to reach the following stop. */
    public int getRunningTime(final int stop) {
        // timeShift is not relevant since this involves updated times and is relative.
        return getArrivalTime(stop + 1) - getDepartureTime(stop);
    }

    /** @return the difference between the scheduled and actual arrival times at this stop. */
    public int getArrivalDelay(final int stop) {
        return getArrivalTime(stop) - (scheduledArrivalTimes[stop] + timeShift);
    }

    /** @return the difference between the scheduled and actual departure times at this stop. */
    public int getDepartureDelay(final int stop) {
        return getDepartureTime(stop) - (scheduledDepartureTimes[stop] + timeShift);
    }

    public int getCallAndRideBoardTime(int stop, long currTime, int directTime, ServiceDay sd, boolean useClockTime, long startClockTime) {
        int travelTime = getDemandResponseMaxTime(directTime);
        int minBoardTime = getArrivalTime(stop + 1) - travelTime;
        int ret = (int) Math.min(Math.max(currTime, getDepartureTime(stop)), minBoardTime);
        if (useClockTime) {
            int clockTime = (int) (sd.secondsSinceMidnight(startClockTime) + Math.round(trip.getDrtAdvanceBookMin() * 60.0));
            if (ret >= clockTime) {
                return ret;
            } else if (clockTime < minBoardTime) {
                return clockTime;
            } else {
                return -1;
            }
        }
        return ret;
    }

    public int getCallAndRideAlightTime(int stop, long currTime, int directTime, ServiceDay sd, boolean useClockTime, long startClockTime) {
        int travelTime = getDemandResponseMaxTime(directTime);
        int maxAlightTime = getDepartureTime(stop - 1) + travelTime;
        int ret = (int) Math.max(Math.min(currTime, getArrivalTime(stop)), maxAlightTime);
        if (useClockTime) {
            int clockTime = (int) (sd.secondsSinceMidnight(startClockTime) + Math.round(trip.getDrtAdvanceBookMin() * 60.0));
            // boarding time must be > clockTime
            int boardTime = ret - travelTime;
            if (boardTime >= clockTime) {
                return ret;
            }
            ret += (clockTime - boardTime);
            if (ret >= maxAlightTime) {
                return -1;
            }
        }
        return ret;
    }

    public int getDemandResponseMaxTime(int directTime) {
        if (maxTravelTime != null) {
            return (int) Math.round(maxTravelTime.process(directTime));
        }
        return directTime;
    }

    public int getDemandResponseAvgTime(int directTime) {
        if (avgTravelTime != null) {
            return (int) Math.round(avgTravelTime.process(directTime));
        }
        return directTime;
    }

    /**
     * @return true if this TripTimes represents an unmodified, scheduled trip from a published
     *         timetable or false if it is a updated, cancelled, or otherwise modified one. This
     *         method differs from {@link #getRealTimeState()} in that it checks whether real-time
     *         information is actually available in this TripTimes.
     */
    public boolean isScheduled() {
        return departureTimes == null && arrivalTimes == null && canceledArrivalTimes.isEmpty() && canceledDepartureTimes.isEmpty();
    }

    /**
     * @return true if this TripTimes is canceled
     */
    public boolean isCanceled() {
        final boolean isCanceled = realTimeState == RealTimeState.CANCELED;
        return isCanceled;
    }

    /**
     * @return the real-time state of this TripTimes
     */
    public RealTimeState getRealTimeState() {
        return realTimeState;
    }

    public void setRealTimeState(final RealTimeState realTimeState) {
        this.realTimeState = realTimeState;
    }

    /** Returns whether this stop allows continuous pickup */
    public int getContinuousPickup(final int stop) {
        return continuousPickup[stop];
    }

    /** Returns whether this stop allows continuous dropoff */
    public int getContinuousDropOff(final int stop) {
        return continuousDropOff[stop];
    }

    /** Returns associated dropoff/pickup radius for this stop*/
    public double getServiceAreaRadius(final int stop) {
        return serviceAreaRadius[stop];
    }

    public String getServiceArea(final int stop) {
        return serviceArea[stop];
    }

    /** Used in debugging / dumping times. */
    public static String formatSeconds(int s) {
        int m = s / 60;
        s = s % 60;
        final int h = m / 60;
        m = m % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    /**
     * When creating a scheduled TripTimes or wrapping it in updates, we could potentially imply
     * negative running or dwell times. We really don't want those being used in routing.
     * This method check that all times are increasing, and logs errors if this is not the case.
     * @return whether the times were found to be increasing.
     */
    public boolean timesIncreasing() {
        final int nStops = scheduledArrivalTimes.length;
        int prevDep = -1;
        for (int s = 0; s < nStops; s++) {
            final int arr = getArrivalTime(s);
            final int dep = getDepartureTime(s);

            if (dep < arr) {
                LOG.error("Negative dwell time in TripTimes at stop index {}.", s);
                return false;
            }
            if (prevDep > arr) {
                LOG.error("Negative running time in TripTimes after stop index {}.", s);
                return false;
            }
            prevDep = dep;
        }
        return true;
    }

    /**
     * Once a trip has been found departing or arriving at an appropriate time, check whether that
     * trip fits other restrictive search criteria such as bicycle and wheelchair accessibility
     * and transfers with minimum time or forbidden transfers.
     */
    public boolean tripAcceptable(final State state0, final int stopIndex) {
        final RoutingRequest options = state0.getOptions();
        final BannedStopSet banned = options.bannedTrips.get(trip.getId());
        if (banned != null && banned.contains(stopIndex)) {
            return false;
        }
        if (options.wheelchairAccessible && trip.getWheelchairAccessible() != 1) {
            return false;
        }
        // Establish whether we have a rented _or_ owned bicycle.
        final boolean bicycle = state0.getNonTransitMode() == TraverseMode.BICYCLE;
        if (bicycle && BikeAccess.fromTrip(trip) != BikeAccess.ALLOWED) {
            return false;
        }
        return true;
    }

    /** Cancel this entire trip */
    public void cancel() {
        /*
        arrivalTimes = new int[getNumStops()];
        Arrays.fill(arrivalTimes, UNAVAILABLE);
        departureTimes = arrivalTimes;
        */

        canceledArrivalTimes.set(0, canceledArrivalTimes.size());
        canceledDepartureTimes.set(0, canceledDepartureTimes.size());

        // Update the real-time state
        realTimeState = RealTimeState.CANCELED;
    }

    public void updateDepartureTime(final int stop, final int time) {
        checkCreateTimesArrays();
        departureTimes[stop] = time;
    }

    public void updateDepartureDelay(final int stop, final int delay) {
        checkCreateTimesArrays();
        departureTimes[stop] = scheduledDepartureTimes[stop] + timeShift + delay;
    }

    public void updateArrivalTime(final int stop, final int time) {
        checkCreateTimesArrays();
        arrivalTimes[stop] = time;
    }

    public void updateArrivalDelay(final int stop, final int delay) {
        checkCreateTimesArrays();
        arrivalTimes[stop] = scheduledArrivalTimes[stop] + timeShift + delay;
    }

    /**
     * If they don't already exist, create arrays for updated arrival and departure times
     * that are just time-shifted copies of the zero-based scheduled departure times.
     */
    private void checkCreateTimesArrays() {
        if (arrivalTimes == null) {
            arrivalTimes = Arrays.copyOf(scheduledArrivalTimes, scheduledArrivalTimes.length);
            departureTimes = Arrays.copyOf(scheduledDepartureTimes, scheduledDepartureTimes.length);
            for (int i = 0; i < arrivalTimes.length; i++) {
                arrivalTimes[i] += timeShift;
                departureTimes[i] += timeShift;
            }

            // Update the real-time state
            realTimeState = RealTimeState.UPDATED;
        }
    }

    public int getNumStops () {
        return scheduledArrivalTimes.length;
    }

    /** Sort TripTimes based on first departure time. */
    @Override
    public int compareTo(final TripTimes other) {
        return this.getDepartureTime(0) - other.getDepartureTime(0);
    }

    @Override
    public TripTimes clone() {
        TripTimes ret = null;
        try {
            ret = (TripTimes) super.clone();
        } catch (final CloneNotSupportedException e) {
            LOG.error("This is not happening.");
        }
        return ret;
    }

   /**
    * Returns a time-shifted copy of this TripTimes in which the vehicle passes the given stop
    * index (not stop sequence number) at the given time. We only have a mechanism to shift the
    * scheduled stoptimes, not the real-time stoptimes. Therefore, this only works on trips
    * without updates for now (frequency trips don't have updates).
    */
    public TripTimes timeShift (final int stop, final int time, final boolean depart) {
        if (arrivalTimes != null || departureTimes != null || !canceledDepartureTimes.isEmpty() || !canceledArrivalTimes.isEmpty()) return null;
        final TripTimes shifted = this.clone();
        // Adjust 0-based times to match desired stoptime.
        final int shift = time - (depart ? getDepartureTime(stop) : getArrivalTime(stop));
        shifted.timeShift += shift; // existing shift should usually (always?) be 0 on freqs
        return shifted;
    }

    /** Just to create uniform getter-syntax across the whole public interface of TripTimes. */
    public int getStopSequence(final int stop) {
        return stopSequences[stop];
    }

    /** @return whether or not stopIndex is considered a timepoint in this TripTimes. */
    public boolean isTimepoint(final int stopIndex) {
        return timepoints.get(stopIndex);
    }

    public boolean isCanceledArrival (final int stopIndex) {
       return canceledArrivalTimes.get(stopIndex);
    }

    public boolean isCanceledDeparture (final int stopIndex) {
        return canceledDepartureTimes.get(stopIndex);
    }

    /**
     * Hash the scheduled arrival/departure times. Used in creating stable IDs for trips across GTFS feed versions.
     * Use hops rather than stops because:
     * a) arrival at stop zero and departure from last stop are irrelevant
     * b) this hash function needs to stay stable when users switch from 0.10.x to 1.0
     */
    public HashCode semanticHash(final HashFunction hashFunction) {
        final Hasher hasher = hashFunction.newHasher();
        for (int hop = 0; hop < getNumStops() - 1; hop++) {
            hasher.putInt(getScheduledDepartureTime(hop));
            hasher.putInt(getScheduledArrivalTime(hop + 1));
        }
        return hasher.hash();
    }

    public void cancelArrivalTime(int i) {
        canceledArrivalTimes.set(i);
    }
    public void unCancelArrivalTime(int i) {
        canceledArrivalTimes.clear(i);
    }

    public void cancelDepartureTime(int i) {
        canceledDepartureTimes.set(i);
    }

    public void unCancelDepartureTime(int i) {
        canceledDepartureTimes.clear(i);
    }

    public boolean isTimeCanceled(int i) {
        return isCanceledArrival(i) || isCanceledDeparture(i) || isCanceled();
    }

    public void propagateDelayBackwards(Integer firstDelay) {
        int numStops = this.getNumStops();

        for (int i = 0; i < numStops; i++) {
            if (this.getArrivalDelay(i) != 0) {
                break;
            }

            this.updateArrivalDelay(i, firstDelay);
            this.updateDepartureDelay(i, firstDelay);
        }
    }

}
