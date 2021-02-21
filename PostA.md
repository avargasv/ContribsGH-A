# ContribsGH-P
## The second way to implement a REST-API in Scala. Asynchronous version using futures.

## 1. Introduction

This third post of the series started with [link to the first post], will explain in detail parts of our second 
solution to a software development problem consisting in: 

*The implementation of a REST service based on the GitHub REST API v3 that responds to a GET request at port 8080 
and address `/org/{org_name}/contributors` with a list of the contributors and the total number of their contributions 
to all repositories of the GitHub organization given by the `org_name` part of the URL.*

As explained in the initial post, our solutions to the problem share a structure consisting of a A REST client, 
a REST server, and a processing module. The difference between solutions reside mainly in the processing 
module, which in the second solution makes use of Scala futures to parallelize the calls made to the REST 
client, in order to overcome the inefficiency of the first solution. The code of this second solution can be 
downloaded from [ContribsGH-P](https://www.example.com).

This post will continue with the top-down presentation of the functions of our programs, explaining in detail
the code of the REST client (common to all the solutions) and the processing module of the second solution.

## 2. The code of our REST client module explained

To understand the code of our REST client, we need to first analyze the following auxiliary function:

```scala
private def processResponseBody[T](url: String) (processPage: Body => List[T]): List[T] = {

  @tailrec
  def processResponsePage(processedPages: List[T], pageNumber: Int): List[T] = {
    val eitherPageBody = getResponseBody(s"$url?page=$pageNumber&per_page=100")
    eitherPageBody match {
      case Right(pageBody) if pageBody.length > 2 =>
        val processedPage = processPage(pageBody)
        processResponsePage(processedPages ++ processedPage, pageNumber + 1)
      case Right(_) =>
        processedPages
      case Left(error) =>
        logger.info(s"processResponseBody error - $error")
        processedPages
    }
  }

  processResponsePage(List.empty[T], 1)
}
```

Now, this is not a function like others we have seen before.

Syntactically, it is a type-parameterized higher-order function, i.e. a function with a type parameter `T`, 
that takes another function as one of its arguments (its second argument is a function that takes a `String` 
and returns a `List[T]`).

Semantically, it is a function that,
- given a string representing the URL of a GET REST request, 
- and a function for extracting from a page of the REST response a list of the elements of type `T` 
contained in that page,
- returns a list whose elements are the `T`s that can be extracted from all the pages of the response.

This makes it a very general function that can return a list of repositories for a given organization, 
or a list of the contributors of a given repository, provided it receives the appropriate URL, and the page
processing function necessary to extract the `T`'s we want from the pages returned by `getResponseBody`. 
The `T`'s can be extracted from the pages using a JSON parser, or any other suitable parsing technique.

Before discussing the code of this function in detail, a short digression on the fundamentals of functional 
programming can be helpful.

>**Functional programming**
>
>Functional programs have a very simple structure: they are made of simple componentes (pure functions), combined in 
simple ways (composition of function calls).
>
>Pure functions are functions in the mathematical sense of the term, they always return the same results when called 
with the same arguments, because the values returned depend only on those arguments and not on any form of mutable state.
>
>Pure function calls are "referentially transparent", i.e. they can be substituted by their returned values at any time 
during the evaluation of a program. This property gives raise to the simplest possible program evaluation model, 
the so-called **substitution model**, which stands in sharp contrast with the convoluted models associated with the 
evaluation of imperative programs (programs made of components that can modify a shared state).
>
>As a consequence, functional programs are simpler to understand, simpler to design, and, in general terms,
simpler to reason about, than imperative programs.
>
>In the same spirit, functional programs use immutable variables, i.e. variables whose values cannot be changed.
Immutable variables are particularly relevant for parallel and distributed programming, where mutability represents 
a big threat to consistency, and a hard conceptual problem to deal with.

In short, programming using pure functions and immutable variables simplifies your life as a developer as it greatly
simplifies the mental model of the programs you write.

That said, to aid in the understanding of the code of `processResponsePage`, we present now an example of 
the simplest way of dealing with state in programs that use only pure functions and immutable variables.
There are many other more sophisticated ways to handle state in functional programs, but this simple 
trick will be enough for our purposes.

>**Dealing with state in a simple functional way**
>
```scala
import scala.annotation.tailrec

def factorial1(n: BigInt): BigInt =
  if (n == 0) 1
  else n * factorial1(n-1)

def factorial2(n: BigInt): BigInt = {
  @tailrec
  def factorialAux(factorialSoFar: BigInt, currentFactor: BigInt): BigInt =
    if (currentFactor == 0) factorialSoFar
    else factorialAux(currentFactor * factorialSoFar, currentFactor - 1)

  factorialAux(1, n)
}

factorial2(12345)
//factorial1(12345)
```
>
>Here we present two different implementations of the ubiquitous factorial function.
>
>- The first implementation is a literal transcription of the standard recursive definition of the function, 
>so simple and clear that needs no explanation. But it has a problem, it is not tail-recursive, that is, the recursive 
>call is not the last operation executed in the body of the recursive function (a multiplication is executed afterwards).
>That makes it vulnerable to stack-overflow errors when calculating the factorial of sufficiently big arguments, as you
>can verify trying to execute the call to `factorial1` commented-out in the example (use a bigger argument if the stack 
>space of your JVM is big enough for the argument of the example).
>
>- Instead, the second implementation uses an auxiliary function `factorialAux` which is tail-recursive, because
>there is nothing waiting to be done at the return of the recursive call. This characteristic of `factorialAux`, 
>together with the `@tailrec` annotation preceding its definition, allows the Scala compiler to optimize its code, 
>replacing the cycle of recursive calls with an equivalent `while` loop. This eliminates the risk of a stack overflow 
>for `factorial2`, as you can verify calling it with the same argument that blew up `factorial1`.
>  
>Besides, `factorialAux` makes use of the trick we mentioned before: it builds in its first argument the factorial 
>of its second argument, using tail-recursion to appropriately update the state consisting of those two arguments 
>until the exit condition of the recursion `(currentFactor == 0)` is reached.
>If this mechanism does not seem clear enough from our description, take a look at an example evaluation of a call to
>`factorial2`, using the already-mentioned substitution model:
>
>**factorial2(3)** ~> factorialAux(1, 3) ~> factorialAux(3, 2) ~> factorialAux(6, 1) ~> factorialAux(6, 0) ~> **6**
 
The auxiliary tail-recursive function `processResponsePage` used by `processResponseBody` uses the same state updating
mechanism illustrated with `factorialAux`, accumulating in its first argument the list of `T`s extracted from the pages
of a REST response until the exit condition of the recursion (to be explained later) is reached.   

The code of the auxiliary function `getResponseBody` used by the REST client module is:

```scala
private def getResponseBody(url: String): Either[Error, Body] = {
  val request =
    if (gh_token != null) Get(url) ~> addHeader("Authorization", gh_token)
    else Get(url)
  val response = Await.result(pipeline(request), timeout)
  response.status match {
    case StatusCodes.OK =>
      Right(response.entity.asString.trim)
    case StatusCodes.Forbidden =>
      Left("API rate limit exceeded")
    case StatusCodes.NotFound =>
      Left("Non-existent organization")
    case _ =>
      Left(s"Unexpected StatusCode ${response.status.intValue}")
  }
}
```

This function is used by `processResponsePage` to get the response to a REST API call, one page at a time. 
It returns instances of the `Either[Error, Body]` type, which allows the caller to distinguish between a 
successful response with an OK status, and an unsuccessful one with any of the other possible status codes.
The `Either[A, B]` type from the standard Scala library is commonly used to wrap the value returned by a function 
that can fail. It has two instances, `Right[B]` used when the function executes successfully, and `Left[A]` used 
when it fails. The type `B`, then, is the type of the value expected from a successful call, and the type `A` is 
used to provide information about the cause of the failure when a call fails. In our case, these types are `Body` 
and `Error`, respectively, both type-synonyms of `String`, coined just to make the signature of `getResponseBody` 
more expressive. By pattern-matching on values of this `Either` type, `processResponsePage` can tell a `Body` from an 
`Error` returned by `getResponseBody`, and process it accordingly, without having to know anything about HTTP return 
status codes and the like.

Taking another look to the pattern matching logic of `processResponsePage`, you will notice two sub-cases of the 
successful case: that with a non-empty body, for which the recursive accumulation of the desired result goes ahead, 
and that with an empty body, which signals the exit from the recursion because corresponds to a "page" following 
the last one.

Using the general processing capabilities of the auxiliary function `processResponseBody` previously presented, the 
main functions of the REST client are extremely simple:

```scala
def reposByOrganization(organization: Organization): List[Repository] = {
  val resp = processResponseBody(s"https://api.github.com/orgs/$organization/repos") { responsePage =>
    val full_name_RE = s""","full_name":"$organization/([^"]+)",""".r
    val full_name_L = (for (full_name_RE(full_name) <- full_name_RE.findAllIn(responsePage)) yield full_name).toList
    full_name_L.map(Repository(_))
  }
  resp
}

def contributorsByRepo(organization: Organization, repo: Repository): List[Contributor] = {
  val resp = processResponseBody(s"https://api.github.com/repos/$organization/${repo.name}/contributors") { responsePage =>
    for (contribJSON <- parse(responsePage).children) yield {
      val contrib = contribJSON.extract[Contribution]
      Contributor(repo.name, contrib.login, contrib.contributions)
    }
  }
  resp
}
```

The code used to extract the data needed by our model classes, makes use of a regular expression parser 
in `reposByOrganization`, and of the Lift JSON parser in `contributorsByRepo`. To understand the reasons
behind this choice of different parsing techniques, let us first deep a little into the code of `contributorsByRepo`.

The complete JSON parsing process is very simple. The first step, `parse(responsePage).children`, converts the 
JSON string containing the representation of several contributors (in the JSON format defined by GitHub) to a 
list of instances of an internal representation of JSON objects (in the format defined by a Lift DSL). Then, 
a `for` cicle converts those JSON objects into instances of our model class, using the `extract` function, 
parameterized by the type of that class (`Contribution`, a class added specifically for this purpose to the 
Scala object `Entities`).

Now, the described process looks fine to extract 2 fields out of instances of the class `Contribution`, which has 19,
but extracting 1 out the 100 fields defined by GitHub for organizations, calls for a simpler technique, if only as a
matter of concision. Using a regular-expression parser in this case works well and demands a lot less boilerplate 
(we spare ourselves the definition of a class with 100 fields, only one of which is really needed).

Incidentally, an interesting aspect of the calls to `processResponseBody` consists in the use of curly braces (instead 
of parenthesis) to delimit the code of its second argument, making the use of this higher-order function syntactically 
similar to the use of a predefined control structure. This kind of syntactic sugar, frequently found in Scala, 
makes easier the design of natural looking DSLs (another positive route on the way to expressiveness).   

## 3. Changes to the specification and their implications for the code of the REST server module

In order to discuss a concrete case of maintenance of a Scala program, this second version of our REST service 
implements a slight variation of the first specification. We have added two parameters to the URL of our REST point,
one to choose between a repository-based or an organization-based accumulation of the contributions,
and another to establish a minimum number of contributions by contributor. The parameter names are "group-level" and
"min-contribs", respectively, the first accepts the values "repo" or "organization" (default), the second any integer 
value (default 0).

To show some examples of the execution of our programs we have taken the organization "revelation", found among the 
first returned from a call to the GitHub REST API requesting all the organizations, that seemed suitable because of 
the reasonable number of their repositories and contributors. A call to our enhanced API for this organization, with 
the default values of the parameters, is equivalent to a call to our previous API. The following figures show part of
the screen displayed by the web client developed for our service, precisely for those two calls.


![revelation2](revelation1S.png)

![revelation2](revelation1P.png)

As you can see, the screen corresponding to the enhanced API has a "Repository" column in  addition to the columns 
for the contributor and the number of contributions of the original API. 
This column shows an "All revelation repos" pseudo-repository for an API call requesting a grouping at the organization 
level. In a similar vein, an "Other contributors" pseudo-contributor is used to accumulate the contributions under 
the minimum, within each repository or within the entire organization depending on the value of the first parameter. 
This allows requests that can can be used to get a high-level overview of the contributions, like the following one:


![revelation2](revelation2.png)
 
where we can see at a glance who are the main contributors to the repos of the "revelation" organization as a whole,
and the relative importance of their contributions.

The enhancement made to our API service has almost trivial implication for the code of our previous implementation:
- There is a change in the model class `Contributor` (one added field for the repository)
- Some changes are needed in the `contributorsByOrganization` function of our REST server module, which now reads:  

```scala
private def contributorsByOrganization(organization: Organization, groupLevel: String, minContribs: Int): List[Contributor] = {
  val repos = reposByOrganization(organization)

  // parallel retrieval of contributors by repo using futures
  val contributorsDetailed: List[Contributor] = RestServerAux.contributorsDetailedFuture(organization, repos)

  // grouping, sorting
  val (contributorsGroupedAboveMin, contributorsGroupedBelowMin) = contributorsDetailed.
    map(c => if (groupLevel == "repo") c else c.copy(repo=s"All $organization repos")).
    groupBy(c => (c.repo, c.contributor)).
    mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
    map(p => Contributor(p._1._1, p._1._2, p._2)).
    partition(_.contributions >= minContribs)

  val contributorsGrouped =
    (
    contributorsGroupedAboveMin
    ++
    contributorsGroupedBelowMin.
      map(c => c.copy(contributor = "Other contributors")).
      groupBy(c => (c.repo, c.contributor)).
      mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
      map(p => Contributor(p._1._1, p._1._2, p._2))
    ).toList.sortWith { (c1: Contributor, c2: Contributor) =>
      if (c1.repo != c2.repo) c1.repo < c2.repo
      else if (c1.contributor == "Other contributors") false
      else if (c1.contributions != c2.contributions) c1.contributions >= c2.contributions
      else c1.contributor < c2.contributor
    }
  contributorsGrouped
}
```

This code is very similar to the previous one, and we expect that the differences are mostly self-explaining.
Perhaps the only ones worth mentioning explicitly are:
- The introduction of pseudo repositories and pseudo contributors - when needed - using `map` with functions that 
return a copy of their arguments with only one of its fields changed.
- The use of `partition` to split a list in two parts according to whether its elements satisfy a splitting condition. 
- The use of `sortWith` to sort the final result by means of a function that implements a more elaborated logic to 
compare two `Contributor`s

## 4. The processing module of our second solution

Going ahead with the discussion of our second solution, the processing module using Scala futures takes the 
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

## 5. Comparison of this solution with the previous one in terms of efficiency

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

## 6. Further alternative solutions to our problem

The next installment of this series will present a third implementation of our REST service, featuring also an 
asynchronous version of the processing module, this time using Akka typed actors.

