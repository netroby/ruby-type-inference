require "mkmf"

RbConfig::MAKEFILE_CONFIG['CC'] = ENV['CC'] if ENV['CC']

require "debase/ruby_core_source"

hdrs = proc {
  have_header("vm_core.h") and
  have_header("iseq.h") and
      have_header("vm_core.h") and
      have_header("vm_insnhelper.h") and
      have_header("method.h")
}

# Allow use customization of compile options. For example, the
# following lines could be put in config_options to to turn off
# optimization:
#   $CFLAGS='-fPIC -fno-strict-aliasing -g3 -ggdb -O2 -fPIC'
config_file = File.join(File.dirname(__FILE__), 'config_options.rb')
load config_file if File.exist?(config_file)

if ENV['debase_debug']
  $CFLAGS+=' -Wall -Werror'
  $CFLAGS+=' -g3'
end

dir_config("ruby")
if !Debase::RubyCoreSource.create_makefile_with_core(hdrs, "arg_scanner")
  STDERR.print("Makefile creation failed\n")
  STDERR.print("*************************************************************\n\n")
  STDERR.print("  NOTE: If your headers were not found, try passing\n")
  STDERR.print("        --with-ruby-include=PATH_TO_HEADERS      \n\n")
  STDERR.print("*************************************************************\n\n")
  exit(1)
end