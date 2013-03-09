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
