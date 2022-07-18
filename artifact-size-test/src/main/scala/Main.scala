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

import cats.effect._
import cats.effect.unsafe.implicits._
import io.circe._
import org.http4s.circe._
import org.http4s.client._
import org.http4s.dom._

object Main {
  val client: Client[IO] = FetchClientBuilder[IO].create

  def main(args: Array[String]): Unit =
    client.expect[Json]("https://api.github.com/repos/http4s/http4s").unsafeRunAndForget()
}
