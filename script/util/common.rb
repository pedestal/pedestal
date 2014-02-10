module Common
  WITHOUT_SNAPSHOT_DEFPROJECT_RE = /\(defproject (io.pedestal\/.+|pedestal-.*\/lein-template) "(\d+\.\d+\.\d+)-SNAPSHOT"/
  WITH_SNAPSHOT_DEFPROJECT_RE = /\(defproject (io.pedestal\/.+|pedestal-.*\/lein-template) "(\d+\.\d+\.\d+-SNAPSHOT)"/

  def check_git_version!
    version_report = `git --version`
    unless version_report =~ /git version ([0-9\.]+)/
      puts "Please install git before running the release script."
      exit -1
    end

    git_version = $1
    puts "Found git #{git_version}"
  end


  def check_credentials_file_exists!(credentials_file)
    unless File.exist?(credentials_file)
      puts "No stored credentials found. Please read https://github.com/technomancy/leiningen/blob/master/doc/DEPLOY.md and place your Clojars deployment credentials in #{credentials_file}"
      exit -1
    end
  end

  def check_credentials_present!(credentials_file)
    found_clojars = false
    IO.popen("gpg -d #{credentials_file}") do |credential_contents|
      credential_contents.each do |line|
        found_clojars = true if line =~ /#"https:\/\/clojars\\\.org\/repo"/
      end
    end
    unless found_clojars
      puts "Credentials plaintext does not appear to have an entry for clojars."
      puts "Please add an entry like the following to your credentials.clj and encrypt it:"
      puts <<-CREDENTIALS_EXAMPLE
        #"https://clojars\\.org/repo"
        {:username "pedestal"
         :password <pedestal clojars password here>}
      CREDENTIALS_EXAMPLE
      exit -1
    end
  end

  def check_credentials!
    credentials_file ="#{ENV['HOME']}/.lein/credentials.clj.gpg"
    check_credentials_file_exists!(credentials_file)
    check_credentials_present!(credentials_file)
  end


  def version_number!(project_cljs, version_re)
    versions = project_cljs.reduce([]) do |versions, project_clj|
      File.open(project_clj) do |file|
        file.each_line do |line|
          versions << $2 if line =~ version_re
        end
      end

      versions
    end

    unless versions.uniq.count == 1
      puts "Found inconsistent version numbers: #{versions.uniq}, aborting."
      exit -1
    end

    versions.uniq.first
  end

  def clean!
    unless system('lein sub clean')
      puts "Failed to clean project directories. Aborting."
      exit -1
    end
  end

  def deploy!
    ["jetty", "tomcat", "service", "service-tools", "service-template"].each do |artifact|
      unless system("cd #{artifact} && echo $PWD && lein deploy clojars")
        puts "Failed deploying #{artifact}. Aborting."
        exit -1
      end
    end
  end

  def bump_version(project_cljs, defproject_re, prev_version, new_version)
    project_cljs.each do |project_clj|
      contents = File.read project_clj
      File.open(project_clj,"w") do |file|
        redefined = contents.gsub(defproject_re, '(defproject \1 "'+ new_version + '"')
        redepended = redefined.gsub(/\[io.pedestal\/(.+) "#{prev_version}"/,
                                    '[io.pedestal/\1 "'+new_version+'"')
        file.puts redepended
      end
    end
  end

end
