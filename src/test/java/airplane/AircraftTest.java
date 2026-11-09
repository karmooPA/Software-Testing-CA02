package airplane;

import airplane.components.Engine;
import airplane.components.FuelSystem;
import airplane.components.LandingGear;
import airplane.components.NavigationSystem;
import airplane.exceptions.FlightException;
import airplane.models.FlightStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static airplane.models.FlightStatus.ABORTED;
import static airplane.models.FlightStatus.GROUNDED;
import static airplane.models.FlightStatus.IN_FLIGHT;
import static airplane.models.FlightStatus.LANDED;
import static airplane.models.FlightStatus.READY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests Aircraft (the SUT) in isolation from its four depended-on components.
 *
 * Stubbed queries (isOperational, hasEnoughFuel, isRouteAvailable) control the SUT's indirect inputs.
 * Verified commands (start, retract, consumeFuel, deploy, stop) observe its indirect outputs.
 */
class AircraftTest {

    private static final double FUEL_PER_LEG = 25.0;

    private static final Named<Consumer<Aircraft>> PREPARE = named("prepareForTakeoff", Aircraft::prepareForTakeoff);
    private static final Named<Consumer<Aircraft>> TAKE_OFF = named("takeOff", Aircraft::takeOff);
    private static final Named<Consumer<Aircraft>> CRUISE = named("cruise", plane -> plane.cruise(FUEL_PER_LEG));
    private static final Named<Consumer<Aircraft>> LAND = named("land", Aircraft::land);
    private static final Named<Consumer<Aircraft>> EMERGENCY_LAND = named("emergencyLand", Aircraft::emergencyLand);

    private Engine engine;
    private FuelSystem fuelSystem;
    private LandingGear landingGear;
    private NavigationSystem navigationSystem;
    private Aircraft aircraft;

    @BeforeEach
    void createAircraftWithMockedComponents() {
        engine = mock(Engine.class);
        fuelSystem = mock(FuelSystem.class);
        landingGear = mock(LandingGear.class);
        navigationSystem = mock(NavigationSystem.class);
        aircraft = new Aircraft(engine, fuelSystem, landingGear, navigationSystem);
    }

    // ---------------------------------------------------------------- creation

    @Test
    void newAircraft_startsGrounded() {
        assertEquals(GROUNDED, aircraft.getStatus());
    }

    @Test
    void constructor_whenAnyComponentIsMissing_throwsNullPointerException() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new Aircraft(null, fuelSystem, landingGear, navigationSystem)),
                () -> assertThrows(NullPointerException.class,
                        () -> new Aircraft(engine, null, landingGear, navigationSystem)),
                () -> assertThrows(NullPointerException.class,
                        () -> new Aircraft(engine, fuelSystem, null, navigationSystem)),
                () -> assertThrows(NullPointerException.class,
                        () -> new Aircraft(engine, fuelSystem, landingGear, null)));
    }

    // ---------------------------------------------------------------- prepareForTakeoff

    @Test
    void prepareForTakeoff_whenAllChecksPass_becomesReady() {
        stubPreflightChecks(true, true, true);

        aircraft.prepareForTakeoff();

        assertEquals(READY, aircraft.getStatus());
    }

    @ParameterizedTest(name = "engine={0}, fuel={1}, route={2} -> refused, naming the {3}")
    @CsvSource({
            "false, true,  true,  engine",
            "true,  false, true,  fuel",
            "true,  true,  false, route"
    })
    void prepareForTakeoff_whenOneCheckFails_isRefusedAndStaysGrounded(boolean engineOperational,
                                                                       boolean enoughFuel,
                                                                       boolean routeAvailable,
                                                                       String reason) {
        stubPreflightChecks(engineOperational, enoughFuel, routeAvailable);

        FlightException refusal = assertThrows(FlightException.class, aircraft::prepareForTakeoff);

        assertTrue(refusal.getMessage().contains(reason), refusal.getMessage());
        assertEquals(GROUNDED, aircraft.getStatus());
    }

    @Test
    void prepareForTakeoff_whenFuelSystemCannotBeRead_doesNotBecomeReady() {
        when(engine.isOperational()).thenReturn(true);
        when(fuelSystem.hasEnoughFuel()).thenThrow(new IllegalStateException("fuel gauge offline"));

        assertThrows(RuntimeException.class, aircraft::prepareForTakeoff);

        assertEquals(GROUNDED, aircraft.getStatus());
    }

    // ---------------------------------------------------------------- takeOff

    @Test
    void takeOff_whenReady_becomesInFlight() {
        prepareAircraft();

        aircraft.takeOff();

        assertEquals(IN_FLIGHT, aircraft.getStatus());
    }

    @Test
    void takeOff_whenReady_startsEngineBeforeRetractingGear() {
        prepareAircraft();

        aircraft.takeOff();

        InOrder order = inOrder(engine, landingGear);
        order.verify(engine).start();
        order.verify(landingGear).retract();
    }

    @Test
    void takeOff_beforePreparation_leavesEngineAndGearUntouched() {
        assertThrows(FlightException.class, aircraft::takeOff);

        verifyNoInteractions(engine, landingGear);
    }

    @Test
    void takeOff_whenEngineFailsToStart_abortsAndReportsTheCause() {
        prepareAircraft();
        RuntimeException startFailure = new IllegalStateException("The engine is not operational.");
        doThrow(startFailure).when(engine).start();

        FlightException thrown = assertThrows(FlightException.class, aircraft::takeOff);

        assertSame(startFailure, thrown.getCause());
        assertEquals(ABORTED, aircraft.getStatus());
    }

    @Test
    void takeOff_whenEngineFailsToStart_neverRetractsGear() {
        prepareAircraft();
        doThrow(new IllegalStateException("The engine is not operational.")).when(engine).start();

        assertThrows(FlightException.class, aircraft::takeOff);

        verify(landingGear, never()).retract();
    }

    @Test
    void takeOff_whenGearFailsToRetract_abortsAndReportsTheCause() {
        prepareAircraft();
        RuntimeException gearFailure = new IllegalStateException("gear jammed");
        doThrow(gearFailure).when(landingGear).retract();

        FlightException thrown = assertThrows(FlightException.class, aircraft::takeOff);

        assertSame(gearFailure, thrown.getCause());
        assertEquals(ABORTED, aircraft.getStatus());
    }

    // ---------------------------------------------------------------- cruise

    @ParameterizedTest(name = "cruise({0}) burns exactly {0}")
    @ValueSource(doubles = {Double.MIN_VALUE, FUEL_PER_LEG})
    void cruise_whenAmountIsPositive_consumesExactlyThatAmountAndKeepsFlying(double amount) {
        putAircraftInFlight();

        aircraft.cruise(amount);

        verify(fuelSystem).consumeFuel(amount);
        assertEquals(IN_FLIGHT, aircraft.getStatus());
    }

    @Test
    void cruise_overSeveralLegs_consumesFuelOncePerLeg() {
        putAircraftInFlight();

        aircraft.cruise(FUEL_PER_LEG);
        aircraft.cruise(FUEL_PER_LEG);
        aircraft.cruise(FUEL_PER_LEG);

        // consumeFuel takes a primitive double: the bare any() returns null, which would fail on unboxing.
        verify(fuelSystem, times(3)).consumeFuel(any(Double.class));
    }

    @ParameterizedTest(name = "cruise({0}) is rejected")
    @ValueSource(doubles = {0.0, -0.01})
    void cruise_whenAmountIsNotPositive_isRejectedWithoutConsumingFuel(double amount) {
        putAircraftInFlight();

        assertThrows(IllegalArgumentException.class, () -> aircraft.cruise(amount));

        verify(fuelSystem, never()).consumeFuel(any(Double.class));
        assertEquals(IN_FLIGHT, aircraft.getStatus());
    }

    @Test
    void cruise_whenEngineIsNoLongerOperational_makesEmergencyLandingWithoutConsumingFuel() {
        putAircraftInFlight();
        when(engine.isOperational()).thenReturn(false);

        aircraft.cruise(FUEL_PER_LEG);

        verify(fuelSystem, never()).consumeFuel(any(Double.class));
        verify(landingGear).deploy();
        verify(engine).stop();
        assertEquals(LANDED, aircraft.getStatus());
    }

    @Test
    void cruise_whenFuelConsumptionFails_makesEmergencyLandingAndReportsTheCause() {
        putAircraftInFlight();
        RuntimeException fuelFailure = new IllegalStateException("Not enough fuel.");
        // consumeFuel is void, so when(...).thenThrow(...) cannot wrap it: the do-family is required.
        doThrow(fuelFailure).when(fuelSystem).consumeFuel(FUEL_PER_LEG);

        FlightException thrown = assertThrows(FlightException.class, () -> aircraft.cruise(FUEL_PER_LEG));

        assertSame(fuelFailure, thrown.getCause());
        verify(landingGear).deploy();
        verify(engine).stop();
        assertEquals(LANDED, aircraft.getStatus());
    }

    @Test
    void cruise_whenFuelRunsOutOnSecondLeg_landsOnlyAfterThatLeg() {
        putAircraftInFlight();
        // Consecutive stubbing: doNothing() lets the first call succeed, then the second one fails.
        doNothing()
                .doThrow(new IllegalStateException("Not enough fuel."))
                .when(fuelSystem).consumeFuel(any(Double.class));

        aircraft.cruise(FUEL_PER_LEG);
        assertEquals(IN_FLIGHT, aircraft.getStatus());

        assertThrows(FlightException.class, () -> aircraft.cruise(FUEL_PER_LEG));
        assertEquals(LANDED, aircraft.getStatus());
    }

    // ---------------------------------------------------------------- land / emergencyLand

    @ParameterizedTest(name = "{0}")
    @MethodSource("landings")
    void landing_whenInFlight_becomesLanded(Consumer<Aircraft> landing) {
        putAircraftInFlight();

        landing.accept(aircraft);

        assertEquals(LANDED, aircraft.getStatus());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("landings")
    void landing_whenInFlight_deploysGearBeforeStoppingEngine(Consumer<Aircraft> landing) {
        putAircraftInFlight();

        landing.accept(aircraft);

        InOrder order = inOrder(landingGear, engine);
        order.verify(landingGear).deploy();
        order.verify(engine).stop();
    }

    static Stream<Arguments> landings() {
        return Stream.of(arguments(LAND), arguments(EMERGENCY_LAND));
    }

    // ---------------------------------------------------------------- flying again

    @Test
    void secondFlight_afterLanding_repeatsChecksAndTakeoffSteps() {
        putAircraftInFlight();
        aircraft.land();
        // Aircraft keeps these same mocks for life, so reset() is what separates the two flights:
        // it drops flight one's recorded calls and stubs, leaving times(1) to count flight two only.
        reset(engine, fuelSystem, landingGear, navigationSystem);
        stubPreflightChecks(true, true, true);

        aircraft.prepareForTakeoff();
        aircraft.takeOff();

        verify(fuelSystem, times(1)).hasEnoughFuel();
        verify(engine, times(1)).start();
        verify(landingGear, times(1)).retract();
        assertEquals(IN_FLIGHT, aircraft.getStatus());
    }

    @Test
    void prepareForTakeoff_afterEmergencyLandingWithBrokenEngine_isRefused() {
        putAircraftInFlight();
        when(engine.isOperational()).thenReturn(false);
        aircraft.cruise(FUEL_PER_LEG);
        assertEquals(LANDED, aircraft.getStatus());

        assertThrows(FlightException.class, aircraft::prepareForTakeoff);

        assertEquals(LANDED, aircraft.getStatus());
    }

    // ---------------------------------------------------------------- lifecycle state machine

    @ParameterizedTest(name = "{1} while {0}")
    @MethodSource("illegalTransitions")
    void lifecycleOperation_whenNotAllowedInCurrentStatus_throwsAndKeepsStatus(FlightStatus status,
                                                                               Consumer<Aircraft> operation) {
        bringAircraftTo(status);
        assertEquals(status, aircraft.getStatus());

        assertThrows(FlightException.class, () -> operation.accept(aircraft));

        assertEquals(status, aircraft.getStatus());
    }

    /** Every status/operation pair the lifecycle forbids; the other six pairs are tested above. */
    static Stream<Arguments> illegalTransitions() {
        return Stream.of(
                arguments(READY, PREPARE),
                arguments(IN_FLIGHT, PREPARE),
                arguments(ABORTED, PREPARE),

                arguments(GROUNDED, TAKE_OFF),
                arguments(IN_FLIGHT, TAKE_OFF),
                arguments(LANDED, TAKE_OFF),
                arguments(ABORTED, TAKE_OFF),

                arguments(GROUNDED, CRUISE),
                arguments(READY, CRUISE),
                arguments(LANDED, CRUISE),
                arguments(ABORTED, CRUISE),

                arguments(GROUNDED, LAND),
                arguments(READY, LAND),
                arguments(LANDED, LAND),
                arguments(ABORTED, LAND),

                arguments(GROUNDED, EMERGENCY_LAND),
                arguments(READY, EMERGENCY_LAND),
                arguments(LANDED, EMERGENCY_LAND),
                arguments(ABORTED, EMERGENCY_LAND));
    }

    // ---------------------------------------------------------------- fixture helpers

    private void stubPreflightChecks(boolean engineOperational, boolean enoughFuel, boolean routeAvailable) {
        when(engine.isOperational()).thenReturn(engineOperational);
        when(fuelSystem.hasEnoughFuel()).thenReturn(enoughFuel);
        when(navigationSystem.isRouteAvailable()).thenReturn(routeAvailable);
    }

    private void prepareAircraft() {
        stubPreflightChecks(true, true, true);
        aircraft.prepareForTakeoff();
    }

    private void putAircraftInFlight() {
        prepareAircraft();
        aircraft.takeOff();
    }

    private void bringAircraftTo(FlightStatus status) {
        switch (status) {
            case GROUNDED -> { }
            case READY -> prepareAircraft();
            case IN_FLIGHT -> putAircraftInFlight();
            case LANDED -> {
                putAircraftInFlight();
                aircraft.land();
            }
            case ABORTED -> {
                prepareAircraft();
                doThrow(new IllegalStateException("The engine is not operational.")).when(engine).start();
                assertThrows(FlightException.class, aircraft::takeOff);
            }
        }
    }
}
