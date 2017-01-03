package io.funcqrs.test.backend

import io.funcqrs._
import io.funcqrs.backend.{ Backend, QueryByTag, QueryByTags, QuerySelectAll }
import io.funcqrs.behavior._
import io.funcqrs.behavior.api.Types
import io.funcqrs.config.{ AggregateConfig, ProjectionConfig }
import io.funcqrs.interpreters.{ Identity, IdentityInterpreter }
import io.funcqrs.projections.EventEnvelop
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject

import scala.collection.{ concurrent, immutable }
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.reflect.ClassTag

class InMemoryBackend extends Backend[Identity] {

  private var aggregateConfigs: concurrent.Map[ClassTag[_], AggregateConfig[_, _, _, _]] = concurrent.TrieMap()
  private var aggregates: concurrent.Map[AggregateId, IdentityAggregateRef[_, _, _]]     = TrieMap()

  private val eventStream: Subject[AnyEvent] = PublishSubject()

  private val stream: Stream[AnyEvent] = Stream()

  protected def aggregateRefById[A: ClassTag, C, E, I <: AggregateId](id: I): InMemoryAggregateRef[A, C, E, I] = {

    type ConfigType = AggregateConfig[A, C, E, I]

    aggregates
      .getOrElseUpdate(
        id, { // build new aggregateRef if not existent

          val config = configLookup[A, C, E, I] {
            aggregateConfigs(ClassTagImplicits[A]).asInstanceOf[ConfigType]
          }

          val behavior = config.behavior(id)
          new InMemoryAggregateRef(id, behavior)
        }
      )
      .asInstanceOf[InMemoryAggregateRef[A, C, E, I]]
  }

  def configure[A: ClassTag, C, E, I](config: AggregateConfig[A, C, E, I]): Backend[Identity] = {
    aggregateConfigs += (ClassTagImplicits[A] -> config)
    this
  }

  def configure(config: ProjectionConfig): Backend[Identity] = {

    // does the event match the query criteria?
    def matchQuery(evt: AnyEvent with MetadataFacet[_]): Boolean = {
      config.query match {
        case QueryByTag(tag)   => evt.tags.contains(tag)
        case QueryByTags(tags) => tags.subsetOf(evt.tags)
        case QuerySelectAll    => true
      }
    }

    def matchQueryWithoutTagging(evt: AnyEvent): Boolean = {
      config.query match {
        case QuerySelectAll => true
        case _              => false
      }
    }

    eventStream.subscribe { evt: AnyEvent =>
      // send even to projections
      def sendToProjection(event: AnyEvent) = {
        // TODO: projections should be interpreted as well to avoid this
        val envelop = EventEnvelop(evt, 1)
        Await.ready(config.projection.onEvent(envelop), 10.seconds)
        ()
      }
      evt match {
        case evt: AnyEvent with MetadataFacet[_] if matchQuery(evt) =>
          sendToProjection(evt)
        case evt: AnyEvent if matchQueryWithoutTagging(evt) =>
          sendToProjection(evt)
        case anyEvent =>
        // do nothing, don't send to projection
      }
    }

    this
  }

  private def publishEvents(evts: immutable.Seq[AnyEvent]): Unit = {
    evts foreach { evt =>
      eventStream.onNext(evt)
    }
  }

  class InMemoryAggregateRef[A, C, E, I <: AggregateId](id: I, behavior: api.Behavior[A, C, E]) extends IdentityAggregateRef[A, C, E] {
    self =>

    private var aggregateState: Option[A] = None

    val interpreter = IdentityInterpreter(behavior)

    def ask(cmd: C): Identity[immutable.Seq[E]] =
      handle(aggregateState, cmd)

    def tell(cmd: C): Unit = {
      ask(cmd)
      () // omit events
    }

    private def handle(state: Option[A], cmd: C): immutable.Seq[E] = {
      val (events, updatedAgg) = interpreter.applyCommand(state, cmd)
      aggregateState = updatedAgg
      publishEvents(events)
      events
    }

    def state(): Identity[A] =
      aggregateState.getOrElse(sys.error("Aggregate is not initialized"))

    def exists(): Identity[Boolean] = aggregateState.isDefined

    def withAskTimeout(timeout: FiniteDuration): AggregateRef[A, C, E, Future] = new AsyncAggregateRef[A, C, E] {

      def timeoutDuration: FiniteDuration = timeout

      def withAskTimeout(timeout: FiniteDuration): AggregateRef[A, C, E, Future] = self.withAskTimeout(timeout)

      def tell(cmd: C): Unit = self.tell(cmd)

      def ask(cmd: C): Future[immutable.Seq[E]] = Future.successful(self.ask(cmd))

      def state(): Future[A] = Future.successful(self.state())

      def exists(): Future[Boolean] = Future.successful(self.exists())
    }
  }
}
