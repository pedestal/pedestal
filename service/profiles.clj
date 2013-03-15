; Copyright 2013 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

{:shared
 {:repositories
  [["relevance-private"
    {:url "http://nexus.thinkrelevance.com/nexus/content/groups/private/"
     :creds :gpg}]
   ["sonatype-oss"
    {:url "https://oss.sonatype.org/content/groups/public/"}]]
  :deploy-repositories
  [["releases"
    {:url "http://nexus.thinkrelevance.com/nexus/content/repositories/private-releases/"
     :creds :gpg}]
   ["snapshots"
    {:url "http://nexus.thinkrelevance.com/nexus/content/repositories/private-snapshots/"
     :creds :gpg }]
   ]}}
