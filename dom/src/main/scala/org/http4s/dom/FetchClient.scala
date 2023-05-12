/*
 * Copyright 2021 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package dom

import cats.effect.Async
import cats.effect.Poll
import cats.effect.Resource
import cats.effect.syntax.all._
import cats.syntax.all._
import org.http4s.client.Client
import org.http4s.headers.Referer
import org.scalajs.dom.AbortController
import org.scalajs.dom.BodyInit
import org.scalajs.dom.Fetch
import org.scalajs.dom.Headers
import org.scalajs.dom.HttpMethod
import org.scalajs.dom.RequestDuplex
import org.scalajs.dom.RequestInit
import org.scalajs.dom.{Response => FetchResponse}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

private[dom] object FetchClient {

  private[dom] def makeClient[F[_]](
      requestTimeout: Duration,
      options: FetchOptions
  )(implicit F: Async[F]): Client[F] = Client[F] { (req: Request[F]) =>
    val requestOptions = req.attributes.lookup(FetchOptions.Key)
    val mergedOptions = requestOptions.fold(options)(options.merge)

    Resource.eval(F.fromPromise(F.delay(supportsRequestStreams))).flatMap {
      supportsRequestStreams =>
        val reqBody =
          if (req.body eq EmptyBody)
            Resource.pure[F, (Request[F], Option[BodyInit])]((req, None))
          else if (supportsRequestStreams && mergedOptions.streamingRequests)
            toReadableStream(req.body).map(Some[BodyInit](_)).tupleLeft(req)
          else
            Resource.eval {
              (if (req.isChunked) req.toStrict(None) else req.pure).mproduct { req =>
                req
                  .body
                  .chunkAll
                  .filter(_.nonEmpty)
                  .map(c => c.toUint8Array: BodyInit)
                  .compile
                  .last
              }
            }

        reqBody.flatMap {
          case (req, body) =>
            Resource
              .makeCaseFull { (poll: Poll[F]) =>
                F.delay(new AbortController()).flatMap { abortController =>
                  val init = new RequestInit {}

                  init.method = req.method.name.asInstanceOf[HttpMethod]
                  init.headers = new Headers(toDomHeaders(req.headers, request = true))
                  body.foreach { body =>
                    init.body = body
                    if (supportsRequestStreams)
                      init.duplex = RequestDuplex.half
                  }
                  init.signal = abortController.signal
                  mergedOptions.cache.foreach(init.cache = _)
                  mergedOptions.credentials.foreach(init.credentials = _)
                  mergedOptions.integrity.foreach(init.integrity = _)
                  mergedOptions.keepAlive.foreach(init.keepalive = _)
                  mergedOptions.mode.foreach(init.mode = _)
                  mergedOptions.redirect.foreach(init.redirect = _)
                  // Referer headers are forbidden in Fetch, but we make a best effort to preserve behavior across clients.
                  // See https://developer.mozilla.org/en-US/docs/Glossary/Forbidden_header_name
                  // If there's a Referer header, it will have more priority than the client's `referrer` (if present)
                  // but less priority than the request's `referrer` (if present).
                  requestOptions
                    .flatMap(_.referrer)
                    .orElse(req.headers.get[Referer].map(_.uri))
                    .orElse(options.referrer)
                    .foreach(referrer => init.referrer = referrer.renderString)
                  mergedOptions.referrerPolicy.foreach(init.referrerPolicy = _)

                  val fetch =
                    F.fromPromiseCancelable(
                      F.delay(Fetch.fetch(req.uri.renderString, init))
                        .tupleRight(F.delay(abortController.abort()))
                    ).timeoutTo(
                      requestTimeout,
                      F.raiseError[FetchResponse](new TimeoutException(
                        s"Request to ${req.uri.renderString} timed out after ${requestTimeout.toMillis} ms"))
                    )

                  poll(fetch)
                }
              } {
                case (r, exitCase) =>
                  Option(r.body).traverse_(cancelReadableStream(_, exitCase))
              }
              .evalMap(fromDomResponse[F])

        }
    }
  }

}
