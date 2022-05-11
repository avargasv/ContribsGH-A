# ContribsGH-A
## The third way to implement a REST-API in Scala. Asynchronous version using actors.

## 1. Introduction

This fourth post of the series started with [link to the first post], will explain in detail parts of our third 
solution to a software development problem consisting in: 

*The implementation of a REST service based on the GitHub REST API v3 that responds to a GET request at port 8080 
and endpoint `/org/{org_name}/contributors` with a list of the contributors and the total number of their contributions 
to all repositories of the GitHub organization given by the `org_name` part of the URL.*

As explained in the initial post, our solutions to the problem share a structure consisting of a A REST client, 
a REST server, and a processing module. The difference between solutions reside mainly in the processing 
module, which in the third solution makes use of Akka actors to parallelize the calls made to the REST 
client, in order to overcome the inefficiency of the first solution. The code of this third solution can be 
downloaded from [ContribsGH-P](https://www.example.com).

This post will explain in detail the processing module of the third solution.

## 2. The processing module of our third solution

Going ahead with the discussion of our third solution, the processing module using Scala Akka actors takes the 
following lines:

```scala
def contributorsDetailedFuture(organization: Organization, repos: List[Repository]): List[Contributor] = {
  val contributorsDetailed_L_F: List[Future[List[Contributor]]] = repos.map { repo =>
    Future { contributorsByRepo(organization, repo) }
  }
  val contributorsDetailed_F_L: Future[List[List[Contributor]]] = Future.sequence(contributorsDetailed_L_F)
  val contributorsDetailed: List[Contributor] = Await.result(contributorsDetailed_F_L, timeout).flatten
  contributorsDetailed
}
```

Here we use a technique that allows us to parallelize the processing of the calls to the REST client made by the 
processing module in an astonishing simple way. Instead of mapping `contributorsByRepo(organization, repo)` to the 
list of our repos, as we did in the sequential version of the processing module, we just map **exactly the same 
expression** inside the placeholder `Future`. This marks the calls to `contributorsByRepo` as executable, precisely, 
in the future. As simple as it sounds.

Now, the result type of the new map expression is`List[Future[List[Contributor]]]`, which can be turned using 
`Future.sequence` into `Future[List[List[Contributor]]]`, exactly what we had in the sequential version, but 
wrapped inside a `Future`. Think a little in the relationship between these two types: from a list of futures 
we arrive to a single future wrapping all of them. Is that what we really need? Resorting to the documentation, 
we can verify that the function `Future.sequence` carries exactly the functionality we are looking for. The search 
for the adequate function, as is frequently the case, was illuminated by a consideration of the input and output 
types involved.

Now, to get the result of a `Future`, we just need to apply `Await.result` to it. This, in our case, will trigger
the parallel execution of our calls to `contributorsByRepo` inside an implicit *execution context*, which will 
automatically assign to these calls separate threads of execution, and will make our main thread wait for the end 
of all these calls for at most the time specified by `timeout`. If after that time limit our `Future` is not yet 
finished, an exception will be raised.

As you can see, the differences between our sequential version and the parallel one using futures, are superficial
in syntactic terms, but semantically very profound. That is a hallmark of many of the deeply expressive programming 
abstractions available in Scala. Futures, in particular, are a wonderful means to hide away the cumbersome details 
of the non-blocking and safe execution of concurrent threads, a well-known nightmare for programmers in general and 
especially for newcomers to the field. 

Our simple example, of course, gives just a first glimpse of the power of the Scala standard library futures. They
can do a lot more for a programmer developing parallel programs. Futures can be composed, they have adequate 
special versions of many of the higher-order function available for collection types (like map, filter, flatMap, etc.),
we can associate them callbacks for their successful or failing results, and a lot more. Interested? You can start 
your own exploration [here](https://docs.scala-lang.org/overviews/core/futures.html).

And that is just the beginning, other Scala libraries can give you even more sophisticated versions of the futures
mechanism, and even alternative conceptual ways of dealing with parallel programming.

## 3. Comparison of this solution with the first one in terms of efficiency

To make a simple comparison of the first two implementation of our REST service, the sequential, named CONTRIBSGH-S, 
and the parallel using futures, named CONTRIBSGH-P, we executed both for the organization "revelation", mentioned in 
the previous section of this post.

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

**ContribsGH-P**

[INFO] ContribsGH-P.log - groupLevel='organization', minContribs=1000

[INFO] ContribsGH-P.log - Starting ContribsGH-P REST API call at 04-12-2020 06:04:39 - organization='revelation'

[INFO] ContribsGH-P.log - # of repos=24

[INFO] ContribsGH-P.log - repo='amazon-linux', # of contributors=0

[INFO] ContribsGH-P.log - repo='almaz', # of contributors=4

...

[INFO] ContribsGH-P.log - repo='rails', # of contributors=358

[INFO] ContribsGH-P.log - Finished ContribsGH-P REST API call at 04-12-2020 06:04:48 - organization='revelation'

[INFO] ContribsGH-P.log - Time elapsed from start to finish: 8.62 seconds

----------------------------------------------------------------------------------------------------------------

As you can see, the parallel version took less than half the time of the sequential one. The times shown are for
a laptop with 2 Intel I7 cores. The organization used for the comparison, "revelation", has 24 repositories. 
We show here the log messages associated to only 3 of them in each case, in the order in which they were displayed 
in the log; enough to appreciate another interesting difference between versions: the order of appearance of those 
messages differ between the sequential and the parallel processing of the GitHub API calls, notwithstanding the fact 
that the repositories were retrieved in the same order in both cases.
This is, of course, a consequence of the different order of arrival of the responses to the REST client calls in 
the parallel case, which depends on the processing time of those calls, and on the assignation of processing threads
to them, that is now in the hands of an execution context implicitly established for running our futures.

## 4. Further alternative solutions to our problem

The next installment of this series will present a fourth version of our REST service, adding a cache to the 
second version implmented by means of a REDIS in-memory non-SQL database.

