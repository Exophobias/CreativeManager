package fr.k0bus.creativemanager.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GenerationSwapTest {
  @Test
  void scheduleFailureLeavesPreviousGenerationAndTaskUntouched() {
    AtomicReference<String> active = new AtomicReference<>("old");
    List<Integer> retired = new ArrayList<>();

    GenerationSwap.Outcome outcome =
        GenerationSwap.activate(
            "new",
            41,
            replacement -> {
              throw new IllegalStateException("simulated scheduler failure");
            },
            active::set,
            retired::add);

    assertFalse(outcome.activated());
    assertEquals(41, outcome.activeTaskId());
    assertEquals("old", active.get());
    assertTrue(retired.isEmpty());
  }

  @Test
  void successfulReloadPublishesBeforeRetiringPreviousTask() {
    AtomicReference<String> active = new AtomicReference<>("old");
    List<String> events = new ArrayList<>();

    GenerationSwap.Outcome outcome =
        GenerationSwap.activate(
            "new",
            41,
            replacement -> {
              events.add("scheduled:" + replacement);
              return 42;
            },
            replacement -> {
              active.set(replacement);
              events.add("published:" + replacement);
            },
            task -> events.add("retired:" + task));

    assertTrue(outcome.activated());
    assertTrue(outcome.oldTaskRetired());
    assertEquals(42, outcome.activeTaskId());
    assertEquals("new", active.get());
    assertEquals(List.of("scheduled:new", "published:new", "retired:41"), events);
  }

  @Test
  void publicationFailureRetiresReplacementAndKeepsPreviousHandle() {
    List<Integer> retired = new ArrayList<>();

    GenerationSwap.Outcome outcome =
        GenerationSwap.activate(
            "new",
            41,
            replacement -> 42,
            replacement -> {
              throw new IllegalStateException("simulated publish failure");
            },
            retired::add);

    assertFalse(outcome.activated());
    assertEquals(41, outcome.activeTaskId());
    assertEquals(List.of(42), retired);
  }

  @Test
  void disabledReplacementScheduleStillRetiresTheOldTaskAfterPublish() {
    AtomicReference<String> active = new AtomicReference<>("old");
    List<Integer> retired = new ArrayList<>();

    GenerationSwap.Outcome outcome =
        GenerationSwap.activate("new", 41, replacement -> -1, active::set, retired::add);

    assertTrue(outcome.activated());
    assertEquals(-1, outcome.activeTaskId());
    assertEquals("new", active.get());
    assertEquals(List.of(41), retired);
  }

  @Test
  void failedOldTaskRetirementKeepsThePublishedReplacementAndReportsTheLeak() {
    AtomicReference<String> active = new AtomicReference<>("old");

    GenerationSwap.Outcome outcome =
        GenerationSwap.activate(
            "new",
            41,
            replacement -> 42,
            active::set,
            task -> {
              throw new IllegalStateException("simulated scheduler cancellation failure");
            });

    assertTrue(outcome.activated());
    assertFalse(outcome.oldTaskRetired());
    assertEquals(42, outcome.activeTaskId());
    assertEquals("new", active.get());
  }
}
