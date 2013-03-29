#! /usr/bin/env ruby

# Pedestal Release Script
#
# This script performs three steps in order to release Pedestal
# artifacts to Clojars:
#
# 1. Without changing any other source files, it removes the -SNAPSHOT
# from the versions of every project.clj. It creates a git commit with
# this new state, and an associated git tag. 
#
# 2. Tracking the release checkpoint in git, it pushes a release of
# the software in its present state to Clojars. It pushes releases in
# a specific order so that pedestal modules which depend on other
# pedestal modules have their dependencies available when they are
# being released.
#
# 3. The version number in each project.clj is incremented, and the
# -SNAPSHOT declaration is replaced on the incremented version number
# of each project.clj. This restores master of pedestal to the
# development stream such that dependencies will seek versions of the
# software that are pre-release, ahead of the just released version of
# pedestal.

### Implementation follows!

# Check git is installed:

version_report = `git --version`
unless version_report =~ /git version ([0-9\.]+)/
  puts "Please install git before running the release script."
  exit -1
end

git_version = $1

puts "Found git #{git_version}"

# Git found. Precalculate the pending release and the new development stream.

project_cljs = Dir['**/project.clj']
versions = []

snapshot_defproject_re = /\(defproject (io.pedestal\/.+|pedestal-.*\/lein-template) "(\d+\.\d+\.\d+)-SNAPSHOT"/

project_cljs.each do |project_clj|
  File.open(project_clj) do |file|
    file.each_line do |line|
      versions << $2 if line =~ snapshot_defproject_re
    end
  end
end

unless versions.uniq.count == 1
  puts "Found inconsistent version numbers: #{versions.uniq}, aborting."
  exit -1
end

release_version = versions.uniq.first
release_version =~ /(\d+\.\d+\.)(\d+)/
bumped_subminor = (($2.to_i)+1).to_s
pre_release_version = "#{$1}#{bumped_subminor}-SNAPSHOT"
release_defproject_re = /\(defproject (io.pedestal\/.+|pedestal-.*\/lein-template) "#{release_version}"/
# Confirm the release operation

puts "Current released version will be #{release_version}"
puts "New development stream version will be #{pre_release_version}"
puts "Continue? (y/n)"
confirmation_response = gets.strip

unless confirmation_response =~ /[Yy](?:[eE][sS])?/
  puts "Aborting release."
  exit -1
end

# Confirmed, DO ALL THE THINGS

# Bump SNAPSHOT versions up to released versions, commit and tag.

project_cljs.each do |project_clj|
  contents = File.read project_clj
  File.open(project_clj,"w") do |file|
    redefined = contents.gsub(snapshot_defproject_re, '(defproject \1 "'+ release_version + '"')
    redepended = redefined.gsub(/\[io.pedestal\/(.+) "#{release_version}-SNAPSHOT"/,
                               '[io.pedestal/\1 "'+release_version+'"')
    file.puts redepended
  end
end

unless system('git add -u') && system("git commit -m \"Prepare #{release_version} release\"") && system("git tag #{release_version}")
  puts "Failed to create release commit. Aborting."
  exit -1
end

# Deploy to Clojars in dependency order.

["jetty", "tomcat", "app", "service", "app-tools", "service-template", "app-template"].each do |artifact|
  unless system("cd #{artifact} && echo $PWD && lein deploy clojars")
    puts "Failed deploying #{artifact}. Aborting."
    exit -1
  end
end

# Update to pre-release SNAPSHOT version, commit, and push.

project_cljs.each do |project_clj|
  contents = File.read project_clj
  File.open(project_clj,"w") do |file|
    redefined = contents.gsub(release_defproject_re, '(defproject \1 "'+ pre_release_version + '"')
    redepended = redefined.gsub(/\[io.pedestal\/(.+) "#{release_version}"/,
                               '[io.pedestal/\1 "'+pre_release_version+'"')
    file.puts redepended
  end
end

unless system('git add -u') && system("git commit -m \"Start #{pre_release_version} development stream\"")
  puts "Failed to create development stream commit. Aborting."
  exit -1
end

puts "Release #{release_version} pushed to Clojars, tagged and committed.\nRelease #{pre_release_version} set as the latest development stream.\n\nDO NOT FORGET TO 'git push' WHEN YOU ARE READY!"
