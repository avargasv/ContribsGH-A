# ContribsGH-A
## The third way to implement a REST-API in Scala. Asynchronous version using actors.

## 1. Introduction

This fourth post of the series started with [link to the first post], will explain in detail parts of our third 
solution to a software development problem consisting in: 

*The implementation of a REST service based on the GitHub REST API v3 that responds to a GET request at port 8080 
and endpoint `/org/{org_name}/contributors` with a list of the contributors and the total number of their contributions 
to all repositories of the GitHub organization given by the `org_name` part of the URL.*

As explained in the initial post, our solutions to the problem share a structure consisting of a REST client, 
a REST server, and a processing module. The difference between solutions reside mainly in the processing 
module, which in the third solution makes use of Akka actors to perform asynchronously the calls to the REST 
client, in order to overcome the inefficiency of the first (completely synchronous) solution. 
The code of this third solution can be downloaded from [ContribsGH-A](https://www.example.com).

This post will explain in detail the processing module of the third solution. To better understand that explanation,
it may be useful to give a very general explanation of Akka first.

## 2. Akka as a platform for developing concurrent and distributed systems.

Basically, Akka actors are distributed objects with (adjustable) behavior and (protected) state. A system is a 
collection of collaborating actors.

In Akka, the processes of a system are implemented using a mechanism of message-passing among its actors
using only actor references (actor instances are never accessed directly), and the messages themselves
(objects of classes defined specifically for the system's purposes). The messages addressed to a given actor 
are stored in a mailbox (one for every actor) from where they are extracted for processing by a scheduler. 
The mailbox is accessed by the scheduler using a queue (first-in, first-out) discipline.

Actors may have mutable state. This doesn't imply the inconsistency risks typical of shared state in
concurrent systems, because state is encapsulated within actors (the only way to determine an actor's state
is by sending it a message and seeing how it responds), and the messages are guaranteed by an Akka actor system 
to be processed in a one-at-a-time fashion.

During the processing of a message an actor can send messages to other actors and even create new actors,
besides changing its internal state and/or its behavior.

Message processing happens in a "single-threaded illusion" with no concerns for the developer regarding locks,
synchronization or any other low-level concurrency primitives. The actor receiving a message is totally decoupled 
from the actor that originated the message.

Akka actors are distributed by default, they go from remote to local by means of an (automatic) process of optimization.
In turn, going from parallel to distributed is, in practical terms, "just a matter of configuration", i.e. it doesn't
imply any changes to the code implementing the actors' behavior.

Actors are organized in a hierarchy. Each actor has a parent and can create child actors, top-level actors are 
children of the so-called "guardian" (a predefined root-level actor).

There are many patterns to model the interaction between actors. We will use two of the most well-known:
the tell pattern and the ask pattern.

The tell pattern is a "fire-and-forget" way of communication, where the actor originating the interaction sends to the 
receiving actor a message meant to be processed in a completely asynchronous way (i.e. where and when the receiving 
actor "decides" to process the message) while the originating actor continues to do their own work. After the message 
is sent, it’s impossible to know if its processing succeeded or failed or even if it was received at all.

The ask pattern, instead, implements a "request-response" way of communication, where the originating actor sends a
request and gets the `Future` of a response from the receiving actor. There are two variants of these pattern,
the first when the request message is sent by an actor (i.e. an object internal to the actor system) and the second 
when the request message is sent by an object external to the actor system. In our processing module we will use
both variants of the ask pattern.

Fault tolerance is implemented, as an important part of the tenets of an Akka actor system, by means of a hierarchy of 
supervisors that decouple communication from failure-handling (supervisors handle failure, actors need not care of it).

Of course, this bird´s eye view of Akka is just an appetizer for a full explanation of Akka that you can find
[here](https://doc.akka.io/docs/akka/current/typed/guide/index.html). 

## 3. The processing module of our third solution

Returning to the discussion of our third solution, the processing module using Scala Akka actors uses the 
following lines:
```scala
// concurrent retrieval of contributors by repo using akka actors
val contributorsDetailed: List[Contributor] = RestServerAux.contributorsDetailedAkka(organization)
```
instead of:
```scala
val repos = reposByOrganization(organization)
// parallel retrieval of contributors by repo using futures
val contributorsDetailed: List[Contributor] = RestServerAux.contributorsDetailedFuture(organization, repos)
```
used by the (parallel using futures) second solution.

Again, we can see that the difference between versions is confined to an auxiliary function called from
an, otherwise immutable, processing module. However, the technologies involved are deeply different,
but all of them are still accessible under the wide-encompassing Scala umbrella. 

The auxiliary function using Scala Akka actors takes the following lines:
```scala
def contributorsDetailedAkka(organization: Organization): List[Contributor] = {
  val contributorsDetailed_F: Future[ContributorsByOrg] = system ? (ref => ReqContributorsByOrg(organization, ref))
  val contributorsDetailed: List[Contributor] = Await.result(contributorsDetailed_F, futureTimeout) match {
    case RespContributorsByOrg(resp, _) => resp
    case _ => List.empty[Contributor]
  }
  contributorsDetailed
}
```
Here we use the "external" ask pattern to communicate our REST non-actor server with an actor system
that will soon be explained in detail. We apply the pattern using the ask operator `?` to, 
precisely, ask the actor system to send the message `ReqContributorsByOrg(organization, ref)`
to the "main actor" (represented in the message by means of its reference `ref`) and to return the response as a
`Future` (in this case a `Future[ContributorsByOrg]`). Note that the first argument of the ask operator is the actor
system itself, and the second an anonymous function having as argument an actor reference (a reference to the 
destination actor) and as body the message to be sent to that actor. The message (`ReqContributorsByOrg`) takes as
its first parameter the name of the organization at hand (of type `Organization`, just a type synonym of `String`).

As we have seen before, to get the result of a `Future` we just need to apply `Await.result` to it. This, in our case,
will allow us to wait for the `Future` of the response of the main actor of our actor system. This main actor will, 
in turn, interact with other actors executing in independent threads, making our main thread wait for at most the time 
specified by `futureTimeout`. If after that time limit our `Future` is not yet finished, an exception will be raised.

The actor system was created a few lines above the code segment just shown by means of the expression:
`
val system: ActorSystem[ContributorsByOrg] = ActorSystem(ContribsGHMain(), "ContribsGh-AkkaSystem")
`
that calls the factory method `ActorSystem`, taking as arguments the main actor and the name of the 
actor system (just a `String`). The main actor is created using another factory method, `ContribsGHMain`, 
a behavior-defining function (a specific kind of functions whose characteristics and benefits are explained 
in the following paragraphs).

Our actors are "typed actors", actors that can only receive messages pertaining to a pre-defined class. 
The class `T` of the receivable messages defines the class of the actors themselves, by means of a function 
that returns a `Behavior[T]`, i.e. a function that defines an actor as a factory of its "behavior" 
(meaning the way an actor reacts to the messages that it can handle).
Some arguments of these behavior factories are typically used as the state-preserving objects of 
the defined actors. An actor updates its state by returning a new behavior with the values of those arguments 
updated (the new behavior is returned as the final step of the processing of the message that originates the 
change of state). This is probably the simplest way of handling state in a purely-functional way, 
as we explained in the third post of this series.

Previous versions of Akka used "untyped actors", i.e. actors whose behavior was in practical terms defined by a 
function of type `Any => Unit` instead of `Behavior[T]`, used by typed actors. 
This change of signature is, of course, a great enhancement, because sending an unacceptable message to an actor 
is now something that can be detected statically at compile-time.
The presence of unhandled messages at run-time was an ugly (and without the help of the compiler, frequent) 
kind of bug when programming with untyped actors.
By the way, the signature of a function that receives anything and returns nothing is probably the worst 
conceivable for a function in a functional programming language (it defines a function that can only be used
for executing a side-effecting action). Even worse, it also leaves aside all the benefits of a statically 
typed language (nothing useful can result from the compiler checking such an inexpressive signature).

Our main actor (defined in the `ContribsGHMain` object):
- Holds a map of organizations and references to their corresponding organization actors. 
- Responds to a `ReqContributorsByOrg` message for an organization using the "internal" ask pattern to request a
  `List[Contributor]` to the actor associated with that organization.

The code of the `organizations` function, which defines the behavior of a `ContribsGHMain` actor, is: 
```scala
def organizations(orgs_M: Map[Organization, ActorRef[ContributorsByOrg]]): Behavior[ContributorsByOrg] =
  Behaviors.receive { (context, message) =>
    message match {
      case ReqContributorsByOrg(org, replyTo) =>
        if (orgs_M.contains(org)) {
          context.ask (orgs_M(org)) ((ref: ActorRef[ContributorsByOrg]) => ReqContributorsByOrg(org, ref)) {
            case Success(resp) => resp.copy(originalSender=replyTo)
            case Failure(_) => RespContributorsByOrg(List.empty[Contributor], replyTo)
          }
          Behaviors.same
        } else {
          context.self ! message
          val contribsGHOrg = context.spawn(ContribsGHOrg(org), org)
          organizations(orgs_M + (org -> contribsGHOrg))
        }
      case RespContributorsByOrg(resp, originalSender) =>
        originalSender ! message
        Behaviors.same
    }
  }
```
Here you can see:
- The state of a `ContribsGHMain` actor, consisting of the single parameter of the function `organizations` 
  (a map that associates a reference to a `ContributorsByOrg` actor to an `Organization`).
- The way in which the two admissible messages for this actor (`ReqContributorsByOrg` and `RespContributorsByOrg`)
  are processed.
- The use of the ask pattern (this time in its "internal" variant) to ask the appropriate `ContributorsByOrg` actor
  a `List` of the `Contributor`s for the current `Organization`.
- The pre-processing of the response of the `ContributorsByOrg` actor (a `Future[RespContributorsByOrg]`), i.e.
  its conversion into the ask's response to the original sender, depending on whether it was a `Success` or a `Failure`: 
  just the received response in the first case and a response with an empty `List` of `Contributor`s in the second 
  (in both cases with the original sender in place of the current actor, as needed to implement the ask pattern).
- How the state is updated when a message arrives asking for the `Contributor`s of an `Organization` that is not
  contained in the map: a new `ContribsGHOrg` actor is added to the map and a new `Behavior` (with an upgraded state)
  returned.
- How, in the case the `Organization` is contained in the map, the `Behavior` returned is the same (the state doesn't
  change).
- The use of the tell pattern (applying the tell operator `!`) to redirect the response received from a `ContribsGHOrg` 
  actor (appropriately pre-processed depending on its `Success` / `Failure` type, as explained before) to the original sender.

Each one of our organization actors (defined in the `ContribsGHOrg` object):
- Holds a map of the repositories of an organizations and references to their corresponding repository actors.
- Responds to a `ReqContributorsByOrg` message requesting the `List[Contributor]` for an organization.
- To respond:
  - accumulates the contributions of the repositories (requested to the actors associated with the repositories
    of the organization),
  - returns the accumulated contributions.
    
This is the code of the `repositories` function, which defines the behavior of a `ContribsGHOrg` actor:
```scala
def repositories(org: Organization, originalSender: ActorRef[ContributorsByOrg],
                 repos_M: Map[Repository, ActorRef[ContributionsByRepo]],
                 reposRemaining: List[Tuple2[Repository, ActorRef[ContributionsByRepo]]],
                 contributorsSoFar: List[Contributor]): Behavior[ContribsGHMain.ContributorsByOrg] =
  Behaviors.receive { (context, message) =>
    message match {
      case ContribsGHMain.ReqContributorsByOrg(org, replyTo) =>
        val repos_L = reposByOrganization(org)
        if (repos_L.length == 0) {
          // retrieves from the cache, if it exists
          if (repos_M.size > 0) {
            for (repo <- repos_M.keys) repos_M(repo) ! ReqContributionsByRepo(context.self)
            repositories(org, replyTo, repos_M, repos_M.toList, List.empty[Contributor])
          } else {
            replyTo ! RespContributorsByOrg(List.empty[Contributor], originalSender)
            Behaviors.same
          }
        } else {
          // detects new repos and repos modified after their update date registered in repos_M
          val newRepos = repos_L.filter(r => !repos_M.keys.map(_.name).toSet.contains(r.name))
          val modifiedRepos = repos_L.filter(r =>
            repos_M.keys.map(_.name).toSet.contains(r.name) &&
              repos_M.keys.find(_.name == r.name).get.updatedAt.compareTo(r.updatedAt) < 0).toSet
          // stops the actors of the repos modified since the last time repos_M was updated
          repos_M.keys.foreach(k => if (modifiedRepos.contains(k)) context.stop(repos_M(k)))
          // send messages to the actors of the map repos_M to accumulate their responses
          if (newRepos.length > 0 || modifiedRepos.size > 0) {
            // using the new repos map that includes new and modified repos
            context.self ! message
            val repos_M_updated = (repos_M -- modifiedRepos) ++
              (newRepos ++ modifiedRepos).map(repo => repo -> context.spawn(ContribsGHRepo(org, repo), repo.name))
            repositories(org, replyTo, repos_M_updated, repos_M_updated.toList, List.empty[Contributor])
          } else {
            // using the old repos map
            for (repo <- repos_M.keys) repos_M(repo) ! ReqContributionsByRepo(context.self)
            repositories(org, replyTo, repos_M, repos_M.toList, List.empty[Contributor])
          }
        }
      case ContribsGHMain.RespContributionsByRepo(repo, resp) =>
        // accumulates the responses of the repositories of the organization
        // returns the performed accumulation after receiving the response of the last repository
        originalSender ! RespContributorsByOrg(contributorsSoFar ++ newContributors, originalSender)
        repositories(org, originalSender, repos_M,
        val newContributors = resp.map(c => Contributor(repo.name, c.contributor, c.contributions))
        if (reposRemaining.length == 1 && reposRemaining.head._1.name == repo.name) {
          List.empty[Tuple2[Repository, ActorRef[ContributionsByRepo]]], List.empty[Contributor])
        } else {
          repositories(org, originalSender, repos_M,
            reposRemaining.filter(_._1.name != repo.name), contributorsSoFar ++ newContributors)
        }
    }
  }
```
Although apparently more complex than the preceding `ContribsGHMain` actors, these `ContribsGHOrg` actors perform
essentially the same work, with the only obvious difference of having to communicate with more than one child actor
to get the necessary information and, consequently, having to accumulate the partial responses for the repositories
before returning the full response for the organization. That is, they:
- Also hold their state as the parameter(s) of their behavior-defining function `repositories`.
- Also make use of the "internal" ask pattern to ask the appropriate `ContributorsByRepo` repository actors
  a `List` of the `Contributor`s for the repositories of the current `Organization`.
- Pre-process the response of the `ContributorsByRepo` actors (a `Future[RespContributorsByRepo]`) depending
  on whether it was a `Success` or a `Failure` in the same way as before.
- Make use of the tell pattern to return the (accumulated) response received from a group of
  `ContribsGHRepo` actors to the original sender.

Besides, the `ContribsGHOrg` actors administrate the accumulation of the partial responses in a way that we hope 
is made sufficiently clear by the comments interspersed within the code of the `repositories` function.
As said before, this is just "house-keeping" code that does not alter the essential purpose of the function. 
  
Finally, each one of our repository actors (defined in the `ContribsGHRepo` object):
- Holds a list of the contributions to a repository.
- Responds to a `ReqContributionsByRepo` message requesting the `List[Contributor]` for a repository.
- To respond:
  - builds the list using the REST client the first time the message is received,
  - afterwards, returns the previously built list without using the REST client again
    (the repository actors work in that sense as a cache).

This is the code of the `contributions` function, which defines the behavior of a `ContribsGHRepo` actor:
```scala
def contributions(org: Organization, repo: Repository, contribs: List[Contributions]) : Behavior[ContribsGHOrg.ContributionsByRepo] =
  Behaviors.receive { (context, message) =>
    message match {
      case ContribsGHOrg.ReqContributionsByRepo(replyTo) =>
        if (contribs.length == 0) {
          val contribs_L = contributorsByRepo(org, repo).map(c => Contributions(c.contributor, c.contributions))
          if (contribs_L.length == 0)
            replyTo ! ContribsGHMain.RespContributionsByRepo(repo, contribs_L)
          else
            context.self ! message
          contributions(org, repo, contribs_L)
        } else {
          replyTo ! ContribsGHMain.RespContributionsByRepo(repo, contribs)
          Behaviors.same
        }
    }
  }
```

## 4. Comparison of this solution with the first one in terms of efficiency

To make a simple comparison of the first and third implementations of our REST service, the synchronous, 
named CONTRIBSGH-S, and the asynchronous using actors, named CONTRIBSGH-A, we executed both for the organization 
"revelation", already mentioned in previous posts of this series.

The following lines show part of a trace of the executions, displayed by the programs to the logger console.

----------------------------------------------------------------------------------------------------------------

**ContribsGH-S**

[INFO] ContribsGH-S.log - Starting ContribsGH-S REST API call at 03-12-2020 06:55:15 - organization='revelation'

[INFO] ContribsGH-S.log - # of repos=24

[INFO] ContribsGH-S.log - repo='globalize2', # of contributors=6

[INFO] ContribsGH-S.log - repo='revelation.github.com', # of contributors=3

...

[INFO] ContribsGH-S.log - repo='ey-cloud-recipes', # of contributors=68

[INFO] ContribsGH-S.log - Finished ContribsGH-S REST API call at 03-12-2020 06:55:35 - organization='revelation'

[INFO] ContribsGH-S.log - Time elapsed from start to finish: 20.03 seconds

----------------------------------------------------------------------------------------------------------------

**ContribsGH-A**

[INFO] ContribsGH-A.log - groupLevel='organization', minContribs=1000

[INFO] ContribsGH-A.log - Starting ContribsGH-A REST API call at 03-19-2020 10:40:44 - organization='revelation'

[INFO] ContribsGH-A.log - # of repos=24

[INFO] ContribsGH-A.log - repo='amazon-linux', # of contributors=0

[INFO] ContribsGH-A.log - repo='closure-library-2099', # of contributors=1

...

[INFO] ContribsGH-A.log - repo='rails', # of contributors=352

[INFO] ContribsGH-A.log - Finished ContribsGH-A REST API call at 03-19-2020 10:40:52 - organization='revelation'

[INFO] ContribsGH-A.log - Time elapsed from start to finish: 8.57 seconds

----------------------------------------------------------------------------------------------------------------

As you can see, the Akka version took less than half the time of the synchronous one. The times shown are for
a laptop with 2 Intel I7 cores. The organization used for the comparison, "revelation", has 24 repositories. 
We show here the log messages associated to only 3 of them in each case, in the order in which they were displayed 
in the log; enough to appreciate another interesting difference between versions: the order of appearance of those 
messages differ between the synchronous and the asynchronous processing of the GitHub API calls, notwithstanding the 
fact that the repositories were retrieved in the same order in both cases. This is, of course, a consequence of the 
different order of arrival of the responses to the REST client calls in the Akka asynchronous case, which depends 
on the processing time of those calls, and on the assignation of processing threads to them, that is now in the 
hands of an execution context implicitly established for running our actors.

The trace of a second call to our service using the same parameters and organization, shows: 

----------------------------------------------------------------------------------------------------------------

[INFO] ContribsGH-A.log - groupLevel='organization', minContribs=1000

[INFO] ContribsGH-A.log - Starting ContribsGH-A REST API call at 03-19-2020 10:43:46 - organization='revelation'

[INFO] ContribsGH-A.log - # of repos=24

...

[INFO] ContribsGH-A.log - Finished ContribsGH-A REST API call at 03-19-2020 10:43:47 - organization='revelation'

[INFO] ContribsGH-A.log - Time elapsed from start to finish: 0.94 seconds

----------------------------------------------------------------------------------------------------------------

Here we can see that the elapsed time is reduced to approximately one tenth of that used by the previous call. 
This exemplifies the huge gains that can be expected from using our repository actors as a cache.

## 5. Further alternative solutions to our problem

The next installment of this series presents a fourth version of our REST service, adding to the second 
version (parallel using futures) a cache implemented by means of a REDIS in-memory key-value database.
You can find it here [link to the fifth post]. 
