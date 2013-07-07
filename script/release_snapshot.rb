#! /usr/bin/env ruby

# Pedestal Snapshot Release Script
#
# This script publishes a SNAPSHOT release of Pedestal
# artifacts to Clojars:

### Implementation follows!

load 'script/util/common.rb'
extend Common

# Check git is installed:
check_git_version!

# Check for encrypted credentials.
check_credentials!

# Pre-requisites met. Precalculate the pending release and the new development stream.

project_cljs = Dir['**/project.clj']

release_version = version_number!(project_cljs)

# Confirm the release operation

puts "Released version will be #{release_version}"
puts "Continue? (y/n)"
confirmation_response = gets.strip

unless confirmation_response =~ /[Yy](?:[eE][sS])?/
  puts "Aborting release."
  exit -1
end

# Confirmed, DO ALL THE THINGS

# Clean up output directories

clean!

# Deploy to Clojars in dependency order.
deploy!

puts "Release #{release_version} pushed to Clojars."
