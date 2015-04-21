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

load 'script/util/common.rb'
extend Common

# Check git is installed:
check_git_version!

# Check for encrypted credentials.
check_credentials!

# Pre-requisites met. Precalculate the pending release and the new development stream.

project_cljs = Dir['**/project.clj']


release_version = version_number!(project_cljs, Common::WITHOUT_SNAPSHOT_DEFPROJECT_RE)
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

# Clean up output directories

clean!

# Bump SNAPSHOT versions up to released versions, commit and tag.

bump_version(project_cljs, Common::WITHOUT_SNAPSHOT_DEFPROJECT_RE, "#{release_version}-SNAPSHOT", release_version)

unless system('git add -u') && system("git commit -m \"Prepare #{release_version} release\"") && system("git tag #{release_version}")
  puts "Failed to create release commit. Aborting."
  exit -1
end

# Deploy to Clojars in dependency order.
deploy!

# Update to pre-release SNAPSHOT version, commit, and push.
bump_version(project_cljs, release_defproject_re, release_version, pre_release_version)

puts "Release #{release_version} pushed to Clojars, tagged and committed.\nRelease #{pre_release_version} set as the latest development stream.\n\nDO NOT FORGET TO 'git push' WHEN YOU ARE READY!"

unless system('git add -u') && system("git commit -m \"Begin #{pre_release_version} development.\"")
  puts "Failed to create post-release version-bump commit. Aborting."
  exit -1
end


