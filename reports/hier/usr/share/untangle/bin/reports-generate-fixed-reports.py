#!@PREFIX@/usr/share/untangle/bin/ut-pycli -f

import sys

reportsApp = uvm.appManager().app("reports");
try:
    reportsApp.runFixedReport()
except:
    # times out after 60 seconds
    # just continue
    pass
    
