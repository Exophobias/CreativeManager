package fr.k0bus.creativemanager.config;

import java.util.Objects;

/** Small prepare-then-publish transaction used by configuration reloads. */
public final class GenerationSwap {
  @FunctionalInterface
  public interface Scheduler<T> {
    int prepare(T replacement);
  }

  @FunctionalInterface
  public interface Publisher<T> {
    void publish(T replacement);
  }

  @FunctionalInterface
  public interface Retirer {
    void retire(int taskId);
  }

  public record Outcome(boolean activated, int activeTaskId, boolean oldTaskRetired) {}

  private GenerationSwap() {}

  /**
   * Prepares the replacement schedule while the old generation remains active, publishes the
   * replacement, and only then retires the prior schedule. A preparation/publication failure keeps
   * the previous handle and retires any newly prepared task.
   */
  public static <T> Outcome activate(
      T replacement,
      int previousTaskId,
      Scheduler<T> scheduler,
      Publisher<T> publisher,
      Retirer retirer) {
    Objects.requireNonNull(replacement, "replacement");
    Objects.requireNonNull(scheduler, "scheduler");
    Objects.requireNonNull(publisher, "publisher");
    Objects.requireNonNull(retirer, "retirer");

    final int replacementTaskId;
    try {
      replacementTaskId = scheduler.prepare(replacement);
      if (replacementTaskId < -1) {
        return new Outcome(false, previousTaskId, false);
      }
    } catch (RuntimeException failure) {
      return new Outcome(false, previousTaskId, false);
    }

    try {
      publisher.publish(replacement);
    } catch (RuntimeException failure) {
      retireQuietly(replacementTaskId, retirer);
      return new Outcome(false, previousTaskId, false);
    }

    boolean retired = retireQuietly(previousTaskId, retirer);
    return new Outcome(true, replacementTaskId, retired);
  }

  private static boolean retireQuietly(int taskId, Retirer retirer) {
    if (taskId < 0) {
      return true;
    }
    try {
      retirer.retire(taskId);
      return true;
    } catch (RuntimeException failure) {
      return false;
    }
  }
}
