/*
 * Copyright (C) 05/06/13 Romain Reuillon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package fr.iscpif.gridscale

package object authentication {

  def cache[T](f: () ⇒ T)(time: Long): () ⇒ T = synchronized {
    var cache = f()
    var current = System.currentTimeMillis
    () ⇒ {
      if (current + time * 1000 < System.currentTimeMillis) {
        cache = f()
        current = System.currentTimeMillis
      }
      cache
    }
  }

  def bind[T](f: () ⇒ T)(b: (() ⇒ T) ⇒ Unit): () ⇒ T =
    () ⇒ {
      b(f)
      f()
    }

  implicit class RenewDecoder[T](f: () ⇒ T) {
    def cache(time: Long) = authentication.cache[T](f)(time)
    def bind(b: (() ⇒ T) ⇒ Unit): () ⇒ T = authentication.bind(f)(b)
  }

}