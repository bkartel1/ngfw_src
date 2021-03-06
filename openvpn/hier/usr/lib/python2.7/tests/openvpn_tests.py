import unittest2
import time
import sys
import pdb
import os
import re
import subprocess
import base64

from jsonrpc import ServiceProxy
from jsonrpc import JSONRPCException
from global_functions import uvmContext
from uvm import Manager
from uvm import Uvm
import remote_control
import test_registry
import global_functions

default_policy_id = 1
appData = None
app = None
appWeb = None
vpnClientName = "atsclient"
vpnFullClientName = "atsfullclient"
vpnHostResult = 0
vpnClientResult = 0 
vpnServerResult = 0
vpnSite2SiteFile = "http://test.untangle.com/test/openvpn-site2site10-config.zip"
vpnSite2SiteHostname = "untangle-268"
vpnSite2SiteUserPassFile = "http://test.untangle.com/test/openvpn-site2siteUserPass-config.zip"
vpnSite2SiteUserPassHostname = "untangle-8003"
tunnelUp = False
ovpnlocaluser = "ovpnlocaluser"
ovpnPasswd = "passwd"

def setUpClient(vpn_enabled=True,vpn_export=False,vpn_exportNetwork="127.0.0.1",vpn_groupId=1,vpn_name=vpnClientName):
    return {
            "enabled": vpn_enabled, 
            "export": vpn_export, 
            "exportNetwork": vpn_exportNetwork, 
            "groupId": vpn_groupId, 
            "javaClass": "com.untangle.app.openvpn.OpenVpnRemoteClient", 
            "name": vpn_name
    }

def create_export(network, name="export", enabled=True):
    return {
        "javaClass": "com.untangle.app.openvpn.OpenVpnExport", 
        "name": name,
        "enabled": enabled,
        "network": network
    }

def waitForServerVPNtoConnect():
    timeout = 60  # wait for up to one minute for the VPN to connect
    while timeout > 0:
        time.sleep(1)
        timeout -= 1
        listOfServers = app.getRemoteServersStatus()
        if (len(listOfServers['list']) > 0):
            if (listOfServers['list'][0]['connected']):
                # VPN has connected
                break
    return timeout

def waitForClientVPNtoConnect():
    timeout = 120  # wait for up to two minute for the VPN to connect
    while timeout > 0:
        time.sleep(1)
        timeout -= 1
        listOfServers = app.getActiveClients()
        if (len(listOfServers['list']) > 0):
            if (listOfServers['list'][0]['clientName']):
                # VPN has connected
                time.sleep(5) # wait for client to get connectivity
                break
    return timeout

def waitForPing(target_IP="127.0.0.1",ping_result_expected=0):
    timeout = 60  # wait for up to one minute for the target ping result
    ping_result = False
    while timeout > 0:
        time.sleep(1)
        timeout -= 1
        result = subprocess.call(["ping","-W","5","-c","1",target_IP],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if (result == ping_result_expected):
            # target is reachable if succ
            ping_result = True
            break
    return ping_result

def configureVPNClientForConnection(clientLink):
    "download client config from passed link, unzip, and copy to correct location"
    #download config
    subprocess.call("wget -o /dev/null -t 1 --timeout=3 http://localhost" + clientLink + " -O /tmp/clientconfig.zip", shell=True)
    #copy config to remote host
    subprocess.call("scp -o 'StrictHostKeyChecking=no' -i " + global_functions.get_prefix() + "/usr/lib/python2.7/tests/test_shell.key /tmp/clientconfig.zip testshell@" + global_functions.vpnClientVpnIP + ":/tmp/>/dev/null 2>&1", shell=True)
    #unzip files
    unzipFiles = remote_control.run_command("sudo unzip -o /tmp/clientconfig.zip -d /tmp/", host=global_functions.vpnClientVpnIP)
    #remove any existing openvpn config files
    removeOld = remote_control.run_command("sudo rm -f /etc/openvpn/*.conf; sudo rm -f /etc/openvpn/*.ovpn; sudo rm -rf /etc/openvpn/keys", host=global_functions.vpnClientVpnIP)
    #move new config to directory
    moveNew = remote_control.run_command("sudo mv -f /tmp/untangle-vpn/* /etc/openvpn/", host=global_functions.vpnClientVpnIP)
    if(unzipFiles == 0) and (removeOld == 0) and (moveNew == 0):
        result = 0
    return result

def createLocalDirectoryUser():
    return {'javaClass': 'java.util.LinkedList', 
        'list': [{
            'username': ovpnlocaluser, 
            'firstName': 'OVPNfname', 
            'lastName': 'OVPNlname', 
            'javaClass': 'com.untangle.uvm.LocalDirectoryUser', 
            'expirationTime': 0, 
            'passwordBase64Hash': base64.b64encode(ovpnPasswd),
            'email': 'test@example.com'
            },]
    }

def removeLocalDirectoryUser():
    return {'javaClass': 'java.util.LinkedList', 
        'list': []
    }

def createDirectoryConnectorSettings(ad_enable=False, radius_enable=False, ldap_secure=False):
    # Need to send Radius setting even though it's not used in this case.
    if ldap_secure == True:
        ldap_port = 636
    else:
        ldap_port = 389
    return {
        "activeDirectorySettings": {
            "LDAPPort": -1,
            "LDAPSecure": True,
            "OUFilter": "",
            "enabled": True,
            "javaClass": "com.untangle.app.directory_connector.ActiveDirectorySettings",
            "servers": {
                "javaClass": "java.util.LinkedList",
                "list": [
                    {
                        "LDAPHost": global_functions.ad_server,
                        "LDAPPort": ldap_port,
                        "LDAPSecure": ldap_secure,
                        "OUFilters": {
                            "javaClass": "java.util.LinkedList",
                            "list": []
                        },
                        "domain": global_functions.ad_domain,
                        "enabled": ad_enable,
                        "javaClass": "com.untangle.app.directory_connector.ActiveDirectoryServer",
                        "superuser": global_functions.ad_admin,
                        "superuserPass": global_functions.ad_password
                    }
                ]
            }
        },
        "apiEnabled": True,
        "apiManualAddressAllowed": False,
        "googleSettings": {
            "javaClass": "com.untangle.app.directory_connector.GoogleSettings",
             "authenticationEnabled": True
        },
        "javaClass": "com.untangle.app.directory_connector.DirectoryConnectorSettings",
        "radiusSettings": {
            "acctPort": 1813,
            "authPort": 1812,
            "authenticationMethod": "PAP",
            "enabled": radius_enable,
            "javaClass": "com.untangle.app.directory_connector.RadiusSettings",
            "server": global_functions.radius_server,
            "sharedSecret": global_functions.radius_server_password
        }
    }

class OpenVpnTests(unittest2.TestCase):

    @staticmethod
    def appName():
        return "openvpn"

    @staticmethod
    def appWebName():
        return "web-filter"

    @staticmethod
    def vendorName():
        return "Untangle"
        
    @staticmethod
    def initialSetUp(self):
        global app, appWeb, appDC, appData, vpnHostResult, vpnClientResult, vpnServerResult, vpnUserPassHostResult, adResult, radiusResult
        if (uvmContext.appManager().isInstantiated(self.appName())):
            raise Exception('app %s already instantiated' % self.appName())
        app = uvmContext.appManager().instantiate(self.appName(), default_policy_id)
        app.start()
        appWeb = None
        appDC = None
        if (uvmContext.appManager().isInstantiated(self.appWebName())):
            raise Exception('app %s already instantiated' % self.appWebName())
        appWeb = uvmContext.appManager().instantiate(self.appWebName(), default_policy_id)
        vpnHostResult = subprocess.call(["ping","-W","5","-c","1",global_functions.vpnServerVpnIP],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        vpnUserPassHostResult = subprocess.call(["ping","-W","5","-c","1",global_functions.vpnServerUserPassVpnIP],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        vpnClientResult = subprocess.call(["ping","-W","5","-c","1",global_functions.vpnClientVpnIP],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        wanIP = uvmContext.networkManager().getFirstWanAddress()
        if vpnClientResult == 0:
            vpnServerResult = remote_control.run_command("ping -W 5 -c 1 " + wanIP, host=global_functions.vpnClientVpnIP)
        else:
            vpnServerResult = 1
        adResult = subprocess.call(["ping","-c","1",global_functions.ad_server],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        radiusResult = subprocess.call(["ping","-c","1",global_functions.radius_server],stdout=subprocess.PIPE,stderr=subprocess.PIPE)

    def setUp(self):
        pass

    # verify client is online
    def test_010_clientIsOnline(self):
        result = remote_control.is_online()
        assert (result == 0)

    def test_011_license_valid(self):
        assert(uvmContext.licenseManager().isLicenseValid(self.appName()))

    def test_020_createVPNTunnel(self):
        global tunnelUp
        tunnelUp = False
        if (vpnHostResult != 0):
            raise unittest2.SkipTest("No paried VPN server available")
        # Download remote system VPN config
        result = subprocess.call("wget -o /dev/null -t 1 --timeout=3 " + vpnSite2SiteFile + " -O /tmp/config.zip", shell=True)
        assert (result == 0) # verify the download was successful
        app.importClientConfig("/tmp/config.zip")
        # wait for vpn tunnel to form
        timeout = waitForServerVPNtoConnect()
        # If VPN tunnel has failed to connect, fail the test,
        assert(timeout > 0)

        remoteHostResult = waitForPing(global_functions.vpnServerVpnLanIP,0)
        assert (remoteHostResult)
        listOfServers = app.getRemoteServersStatus()
        # print(listOfServers)
        assert(listOfServers['list'][0]['name'] == vpnSite2SiteHostname)
        tunnelUp = True

    def test_030_disableRemoteClientVPNTunnel(self):
        global tunnelUp 
        if (not tunnelUp):
            raise unittest2.SkipTest("previous test test_020_createVPNTunnel failed")
        appData = app.getSettings()
        # print(appData)
        i=0
        found = False
        for remoteGuest in appData['remoteServers']['list']:
            if (remoteGuest['name'] == vpnSite2SiteHostname):
                found = True 
            if (not found):
                i+=1
        assert (found) # test profile not found in remoteServers list
        appData['remoteServers']['list'][i]['enabled'] = False
        app.setSettings(appData)
        remoteHostResult = waitForPing(global_functions.vpnServerVpnLanIP,1)
        assert (remoteHostResult)
        tunnelUp = False

        #remove server from remoteServers so it doesn't interfere with later tests
        appData = app.getSettings()
        appData["remoteServers"]["list"][:] = []
        app.setSettings(appData)

    def test_035_createVPNTunnel_userpass(self):
        """Create Site-to-Site connection with local username/password authentication"""
        if (vpnUserPassHostResult != 0):
            raise unittest2.SkipTest("User/Pass VPN server not available")

        # Download remote system VPN config
        result = subprocess.call("wget -o /dev/null -t 1 --timeout=3 " + vpnSite2SiteUserPassFile + " -O /tmp/UserPassConfig.zip", shell=True)
        assert(result == 0) #verify download was successful
        app.importClientConfig("/tmp/UserPassConfig.zip")

        #set username/password in remoteServer settings
        appData = app.getSettings()
        appData["serverEnabled"]=True
        appData['exports']['list'].append(create_export("192.0.2.0/24")) # append in case using LXC
        appData["remoteServers"]["list"][0]["authUserPass"]=True
        appData["remoteServers"]["list"][0]["authUsername"]=ovpnlocaluser
        appData["remoteServers"]["list"][0]["authPassword"]=ovpnPasswd
        #enable user/password authentication, set to local directory
        appData['authUserPass']=True
        appData["authenticationType"]="LOCAL_DIRECTORY"
        app.setSettings(appData)

        #wait for vpn tunnel to form
        timeout = waitForServerVPNtoConnect()
        # If VPN tunnel has failed to connect, fail the test,
        assert(timeout > 0)

        remoteHostResultUserPass = waitForPing(global_functions.vpnServerUserPassVpnLanIP,0)
        assert(remoteHostResultUserPass)
        listOfServers = app.getRemoteServersStatus()
        #print(listOfServers)
        assert(listOfServers["list"][0]['name'] == vpnSite2SiteUserPassHostname)

        #remove server from remoteServers so it doesn't interfere with later tests
        appData = app.getSettings()
        appData['authUserPass']=False
        appData["remoteServers"]["list"][:] = []
        app.setSettings(appData)

    def test_040_createClientVPNTunnel(self):
        global appData, vpnServerResult, vpnClientResult
        if (vpnClientResult != 0 or vpnServerResult != 0):
            raise unittest2.SkipTest("No paried VPN client available")

        pre_events_connect = global_functions.get_app_metric_value(app,"connect")
        
        running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP,)
        loopLimit = 5
        while ((running == 0) and (loopLimit > 0)):
            # OpenVPN is running, wait 5 sec to see if openvpm is done
            loopLimit -= 1
            time.sleep(5)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if loopLimit == 0:
            # try killing the openvpn session as it is probably stuck
            remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
            time.sleep(2)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if running == 0:
            raise unittest2.SkipTest("OpenVPN test machine already in use")
            
        appData = app.getSettings()
        appData["serverEnabled"]=True
        siteName = appData['siteName']
        appData['exports']['list'].append(create_export("192.0.2.0/24")) # append in case using LXC
        appData['remoteClients']['list'][:] = []  
        appData['remoteClients']['list'].append(setUpClient())
        app.setSettings(appData)
        clientLink = app.getClientDistributionDownloadLink(vpnClientName,"zip")
        # print(clientLink)

        #download, unzip, move config to correct directory
        result = configureVPNClientForConnection(clientLink)
        assert(result == 0)

        #start openvpn tunnel
        remote_control.run_command("cd /etc/openvpn; sudo nohup openvpn "+siteName+".conf >/dev/null 2>&1 &", host=global_functions.vpnClientVpnIP)

        timeout = waitForClientVPNtoConnect()
        # If VPN tunnel has failed to connect so fail the test,
        assert(timeout > 0)
        # ping the test host behind the Untangle from the remote testbox
        result = remote_control.run_command("ping -c 2 " + remote_control.clientIP, host=global_functions.vpnClientVpnIP)
        
        listOfClients = app.getActiveClients()
        print("address " + listOfClients['list'][0]['address'])
        print("vpn address 1 " + listOfClients['list'][0]['poolAddress'])

        host_result = remote_control.run_command("host test.untangle.com", stdout=True)
        # print("host_result <%s>" % host_result)
        match = re.search(r'address \d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}', host_result)
        ip_address_testuntangle = (match.group()).replace('address ','')

        # stop the vpn tunnel on remote box
        remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
        time.sleep(3) # openvpn takes time to shut down

        assert(result==0)
        assert(listOfClients['list'][0]['address'] == global_functions.vpnClientVpnIP)

        events = global_functions.get_events('OpenVPN','Connection Events',None,1)
        assert(events != None)
        found = global_functions.check_events( events.get('list'), 5,
                                            'remote_address', global_functions.vpnClientVpnIP,
                                            'client_name', vpnClientName )
        assert( found )

        # Check to see if the faceplate counters have incremented. 
        post_events_connect = global_functions.get_app_metric_value(app, "connect")
        assert(pre_events_connect < post_events_connect)
        
    def test_050_createClientVPNFullTunnel(self):
        global appData, vpnServerResult, vpnClientResult
        if remote_control.quickTestsOnly:
            raise unittest2.SkipTest('Skipping a time consuming test')
        if (vpnClientResult != 0 or vpnServerResult != 0):
            raise unittest2.SkipTest("No paried VPN client available")
        running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if running == 0:
            raise unittest2.SkipTest("OpenVPN test machine already in use")
        appData = app.getSettings()
        appData["serverEnabled"]=True
        siteName = appData['siteName']  
        appData['remoteClients']['list'][:] = []  
        appData['remoteClients']['list'].append(setUpClient(vpn_name=vpnFullClientName))
        appData['groups']['list'][0]['fullTunnel'] = True
        appData['groups']['list'][0]['fullTunnel'] = True
        app.setSettings(appData)
        clientLink = app.getClientDistributionDownloadLink(vpnFullClientName,"zip")
        # print(clientLink)

        # download client config file
        configureVPNClientForConnection(clientLink)

        #connect openvpn tunnel
        remote_control.run_command("cd /etc/openvpn; sudo nohup openvpn "+siteName+".conf >/dev/null 2>&1 &", host=global_functions.vpnClientVpnIP)

        result1 = 1
        tries = 40
        while result1 != 0 and tries > 0:
            time.sleep(1)
            tries -= 1

            listOfClients = app.getActiveClients()
            if len(listOfClients['list']):
                vpnPoolAddressIP = listOfClients['list'][0]['poolAddress']

                # ping the test host behind the Untangle from the remote testbox
                print("vpn pool address: " + vpnPoolAddressIP)
                result1 = subprocess.call("ping -c1 " + vpnPoolAddressIP + " >/dev/null 2>&1", shell=True)
        if result1 == 0:        
            result2 = remote_control.run_command("ping -c 2 " + remote_control.clientIP, host=vpnPoolAddressIP)

            # run a web request to internet and make sure it goes through web filter
            # webresult = remote_control.run_command("wget -q -O - http://www.playboy.com | grep -q blockpage", host=vpnPoolAddressIP)
            webresult = remote_control.run_command("wget --timeout=4 -q -O - http://www.playboy.com | grep -q blockpage", host=vpnPoolAddressIP)

            print("result1 <%d> result2 <%d> webresult <%d>" % (result1,result2,webresult))
        else:
            print("No VPN IP address found")
        # Shutdown VPN on both sides.
        # Delete profile on server
        appData['remoteClients']['list'][:] = []  
        app.setSettings(appData)
        time.sleep(5) # wait for vpn tunnel to go down 
        # kill the client side
        remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
        time.sleep(3) # openvpn takes time to shut down
        # print(("result " + str(result) + " webresult " + str(webresult)))
        assert(result1==0)
        assert(result2==0)
        assert(listOfClients['list'][0]['address'] == global_functions.vpnClientVpnIP)
        assert(webresult==0)

    def test_060_createDeleteClientVPNTunnel(self):
        global appData, vpnServerResult, vpnClientResult
        if(vpnClientResult != 0 or vpnServerResult != 0):
            raise unittest2.SkipTest("No paried VPN client available")
        
        pre_events_connect = global_functions.get_app_metric_value(app, "connect")

        running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        loopLimit = 5
        while((running == 0) and (loopLimit > 0)):
            # OpenVPN is running, wait 5 sec to see if openvpn is done
            loopLimit -= 1
            time.sleep(5)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if loopLimit == 0:
            # openvpn is probably stuck, kill it and re-run
            remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
            time.sleep(2)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if running == 0:
            raise unittest2.SkipTest("OpenVPN test machine already in use")

        appData = app.getSettings()
        appData["serverEnabled"] = True
        siteName = appData['siteName']
        appData['exports']['list'].append(create_export("192.0.2.0/24")) # append in case using LXC
        appData['remoteClients']['list'][:] = []
        appData['remoteClients']['list'].append(setUpClient())
        app.setSettings(appData)
        #print(appData)
        clientLink = app.getClientDistributionDownloadLink(vpnClientName, "zip")
        print(clientLink)
        
        #download, unzip, move config to correct directory
        result = configureVPNClientForConnection(clientLink)
        assert(result == 0)

        #start openvpn tunnel
        remote_control.run_command("cd /etc/openvpn; sudo nohup openvpn "+siteName+".conf >/dev/null 2>&1 &", host=global_functions.vpnClientVpnIP)

        timeout = waitForClientVPNtoConnect()
        # fail test if vpn tunnel does not connect
        assert(timeout > 0) 
        result = remote_control.run_command("ping -c 2 " + remote_control.clientIP, host=global_functions.vpnClientVpnIP)

        listOfClients = app.getActiveClients()
        print("address " + listOfClients['list'][0]['address'])
        print("vpn address 1 " + listOfClients['list'][0]['poolAddress'])

        host_result = remote_control.run_command("host test.untangle.com", stdout=True)
        print("host_result <%s>" % host_result)
        match = re.search(r'address \d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}', host_result)
        ip_address_testuntangle = (match.group()).replace('address ','')

        #stop the vpn tunnel
        remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
        time.sleep(3) # wait for openvpn to stop
        
        assert(result==0)
        assert(listOfClients['list'][0]['address'] == global_functions.vpnClientVpnIP)

        events = global_functions.get_events('OpenVPN','Connection Events',None,1)
        assert(events != None)
        found = global_functions.check_events( events.get('list'), 5,
                                            'remote_address', global_functions.vpnClientVpnIP,
                                            'client_name', vpnClientName )
        assert( found )

        #check to see if the faceplate counters have incremented
        post_events_connect = global_functions.get_app_metric_value(app, "connect")
        assert(pre_events_connect < post_events_connect)

        #delete the user
        appData['remoteClients']['list'][:] = []
        app.setSettings(appData)

        #attempt to connect with now deleted user
        remote_control.run_command("cd /etc/openvpn; sudo nohup openvpn "+siteName+".conf >/dev/null 2>&1 &", host=global_functions.vpnClientVpnIP)
        timeout = waitForClientVPNtoConnect()
        #fail the test if it does connect
        assert(timeout <= 0)

        #create the same user again
        appData['exports']['list'].append(create_export("192.0.2.0/24")) # append in case using LXC
        appData['remoteClients']['list'][:] = []
        appData['remoteClients']['list'].append(setUpClient())
        app.setSettings(appData)
        #print(appData)
        clientLink = app.getClientDistributionDownloadLink(vpnClientName, "zip")
        print(clientLink)

        #download, unzip, move config to correct directory
        result = configureVPNClientForConnection(clientLink)
        assert(result == 0)

        #check the key files to make sure they aren't O length
        for x in range(0, 3):
            if x == 0:
                crtSize = remote_control.run_command("du /etc/openvpn/keys/" + siteName + "-" + vpnClientName + ".crt",host=global_functions.vpnClientVpnIP,stdout=True)
                fileSize = int(crtSize[0])
            elif x == 1:
                cacrtSize = remote_control.run_command("du /etc/openvpn/keys/" + siteName + "-" + vpnClientName + "-ca.crt",host=global_functions.vpnClientVpnIP,stdout=True)
                fileSize = int(cacrtSize[0])
            elif x == 2:
                keySize = remote_control.run_command("du /etc/openvpn/keys/" + siteName + "-" + vpnClientName + ".key",host=global_functions.vpnClientVpnIP,stdout=True)
                fileSize = int(keySize[0])
            assert(fileSize > 0)

        #start openvpn
        remote_control.run_command("cd /etc/openvpn; sudo nohup openvpn "+siteName+".conf >/dev/null 2>&1 &", host=global_functions.vpnClientVpnIP)
        timeout = waitForClientVPNtoConnect()
        # fail test if vpn tunnel does not connect
        assert(timeout > 0) 
        result = remote_control.run_command("ping -c 2 " + remote_control.clientIP, host=global_functions.vpnClientVpnIP)

        listOfClients = app.getActiveClients()
        print("address " + listOfClients['list'][0]['address'])
        print("vpn address 1 " + listOfClients['list'][0]['poolAddress'])

        host_result = remote_control.run_command("host test.untangle.com", stdout=True)
        print("host_result <%s>" % host_result)
        match = re.search(r'address \d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}', host_result)
        ip_address_testuntangle = (match.group()).replace('address ','')

        #stop the vpn tunnel
        remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
        time.sleep(3) # wait for openvpn to stop
        
        assert(result==0)
        assert(listOfClients['list'][0]['address'] == global_functions.vpnClientVpnIP)

        events = global_functions.get_events('OpenVPN','Connection Events',None,1)
        assert(events != None)
        found = global_functions.check_events( events.get('list'), 5,
                                            'remote_address', global_functions.vpnClientVpnIP,
                                            'client_name', vpnClientName )
        assert( found )

        #check to see if the faceplate counters have incremented
        post_events_connect = global_functions.get_app_metric_value(app, "connect")
        assert(pre_events_connect < post_events_connect)

    def test_070_createClientVPNTunnelLocalUserPass(self):
        global appData, vpnServerResult, vpnClientResult
        if (vpnClientResult != 0 or vpnServerResult != 0):
            raise unittest2.SkipTest("No paried VPN client available")

        pre_events_connect = global_functions.get_app_metric_value(app,"connect")
        
        running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP,)
        loopLimit = 5
        while ((running == 0) and (loopLimit > 0)):
            # OpenVPN is running, wait 5 sec to see if openvpn is done
            loopLimit -= 1
            time.sleep(5)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if loopLimit == 0:
            # try killing the openvpn session as it is probably stuck
            remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
            time.sleep(2)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if running == 0:
            raise unittest2.SkipTest("OpenVPN test machine already in use")
            
        appData = app.getSettings()
        appData["serverEnabled"]=True
        siteName = appData['siteName']
        appData['exports']['list'].append(create_export("192.0.2.0/24")) # append in case using LXC
        appData['remoteClients']['list'][:] = []  
        appData['remoteClients']['list'].append(setUpClient())
        #enable user/password authentication, set to local directory
        appData['authUserPass']=True
        appData["authenticationType"]="LOCAL_DIRECTORY"
        app.setSettings(appData)
        clientLink = app.getClientDistributionDownloadLink(vpnClientName,"zip")

        #create Local Directory User for authentication
        uvmContext.localDirectory().setUsers(createLocalDirectoryUser())

        #download, unzip, move config to correct directory
        result = configureVPNClientForConnection(clientLink)
        assert(result == 0)
        
        #create credentials file containing username/password
        remote_control.run_command("echo " + ovpnlocaluser + " > /tmp/authUserPassFile; echo " + ovpnPasswd + " >> /tmp/authUserPassFile", host=global_functions.vpnClientVpnIP)
        #connect to openvpn using the file
        remote_control.run_command("cd /etc/openvpn; sudo nohup openvpn --config " + siteName + ".conf --auth-user-pass /tmp/authUserPassFile >/dev/null 2>&1 &", host=global_functions.vpnClientVpnIP)

        timeout = waitForClientVPNtoConnect()
        # fail if tunnel doesn't connect
        assert(timeout > 0)
        # ping the test host behind the Untangle from the remote testbox
        result = remote_control.run_command("ping -c 2 " + remote_control.clientIP, host=global_functions.vpnClientVpnIP)
        
        listOfClients = app.getActiveClients()
        print("address " + listOfClients['list'][0]['address'])
        print("vpn address 1 " + listOfClients['list'][0]['poolAddress'])

        host_result = remote_control.run_command("host test.untangle.com", stdout=True)
        match = re.search(r'address \d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}', host_result)
        ip_address_testuntangle = (match.group()).replace('address ','')

        # stop the vpn tunnel on remote box
        remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
        # openvpn takes time to shut down
        time.sleep(3) 

        assert(result==0)
        assert(listOfClients['list'][0]['address'] == global_functions.vpnClientVpnIP)

        events = global_functions.get_events('OpenVPN','Connection Events',None,1)
        assert(events != None)
        found = global_functions.check_events( events.get('list'), 5,
                                            'remote_address', global_functions.vpnClientVpnIP,
                                            'client_name', vpnClientName )
        assert( found )

        # Check to see if the faceplate counters have incremented. 
        post_events_connect = global_functions.get_app_metric_value(app, "connect")
        assert(pre_events_connect < post_events_connect)

        #remove Local Directory User
        uvmContext.localDirectory().setUsers(removeLocalDirectoryUser())        

    def test_075_createClientVPNTunnelRadiusUserPass(self):
        global appData, vpnServerResult, vpnClientResult, appDC
        if (vpnClientResult != 0 or vpnServerResult != 0):
            raise unittest2.SkipTest("No paried VPN client available")

        pre_events_connect = global_functions.get_app_metric_value(app,"connect")

        if (radiusResult != 0):
            raise unittest2.SkipTest("No RADIUS server available")
        appNameDC = "directory-connector"
        if (uvmContext.appManager().isInstantiated(appNameDC)):
            print("App %s already installed" % appNameDC)
            appDC = uvmContext.appManager().app(appNameDC)
        else:
            appDC = uvmContext.appManager().instantiate(appNameDC, default_policy_id)
        appDC.setSettings(createDirectoryConnectorSettings(radius_enable=True))
        
        running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP,)
        loopLimit = 5
        while ((running == 0) and (loopLimit > 0)):
            # OpenVPN is running, wait 5 sec to see if openvpn is done
            loopLimit -= 1
            time.sleep(5)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if loopLimit == 0:
            # try killing the openvpn session as it is probably stuck
            remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
            time.sleep(2)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if running == 0:
            raise unittest2.SkipTest("OpenVPN test machine already in use")
            
        appData = app.getSettings()
        appData["serverEnabled"]=True
        siteName = appData['siteName']
        appData['exports']['list'].append(create_export("192.0.2.0/24")) # append in case using LXC
        appData['remoteClients']['list'][:] = []  
        appData['remoteClients']['list'].append(setUpClient())
        #enable user/password authentication, set to RADIUS directory
        appData['authUserPass']=True
        appData["authenticationType"]="RADIUS"
        app.setSettings(appData)
        clientLink = app.getClientDistributionDownloadLink(vpnClientName,"zip")

        #download, unzip, move config to correct directory
        result = configureVPNClientForConnection(clientLink)
        assert(result == 0)
        
        #create credentials file containing username/password
        remote_control.run_command("echo " + global_functions.radius_user + " > /tmp/authUserPassFile; echo " + global_functions.radius_password + " >> /tmp/authUserPassFile", host=global_functions.vpnClientVpnIP)
        #connect to openvpn using the file
        remote_control.run_command("cd /etc/openvpn; sudo nohup openvpn --config " + siteName + ".conf --auth-user-pass /tmp/authUserPassFile >/dev/null 2>&1 &", host=global_functions.vpnClientVpnIP)

        timeout = waitForClientVPNtoConnect()
        # fail if tunnel doesn't connect
        assert(timeout > 0)
        # ping the test host behind the Untangle from the remote testbox
        result = remote_control.run_command("ping -c 2 " + remote_control.clientIP, host=global_functions.vpnClientVpnIP)
        
        listOfClients = app.getActiveClients()
        print("address " + listOfClients['list'][0]['address'])
        print("vpn address 1 " + listOfClients['list'][0]['poolAddress'])

        host_result = remote_control.run_command("host test.untangle.com", stdout=True)
        match = re.search(r'address \d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}', host_result)
        ip_address_testuntangle = (match.group()).replace('address ','')

        # stop the vpn tunnel on remote box
        remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
        # openvpn takes time to shut down
        time.sleep(3) 

        assert(result==0)
        assert(listOfClients['list'][0]['address'] == global_functions.vpnClientVpnIP)

        events = global_functions.get_events('OpenVPN','Connection Events',None,1)
        assert(events != None)
        found = global_functions.check_events( events.get('list'), 5,
                                            'remote_address', global_functions.vpnClientVpnIP,
                                            'client_name', vpnClientName )
        assert( found )

        # Check to see if the faceplate counters have incremented. 
        post_events_connect = global_functions.get_app_metric_value(app, "connect")
        assert(pre_events_connect < post_events_connect)


    def test_079_createClientVPNTunnelADUserPass(self):
        global appData, vpnServerResult, vpnClientResult, appDC
        if (vpnClientResult != 0 or vpnServerResult != 0):
            raise unittest2.SkipTest("No paried VPN client available")

        pre_events_connect = global_functions.get_app_metric_value(app,"connect")

        if (adResult != 0):
            raise unittest2.SkipTest("No AD server available")
        appNameDC = "directory-connector"
        if (uvmContext.appManager().isInstantiated(appNameDC)):
            print("App %s already installed" % appNameDC)
            appDC = uvmContext.appManager().app(appNameDC)
        else:
            appDC = uvmContext.appManager().instantiate(appNameDC, default_policy_id)
        appDC.setSettings(createDirectoryConnectorSettings(ad_enable=True))
        
        running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP,)
        loopLimit = 5
        while ((running == 0) and (loopLimit > 0)):
            # OpenVPN is running, wait 5 sec to see if openvpn is done
            loopLimit -= 1
            time.sleep(5)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if loopLimit == 0:
            # try killing the openvpn session as it is probably stuck
            remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
            time.sleep(2)
            running = remote_control.run_command("pidof openvpn", host=global_functions.vpnClientVpnIP)
        if running == 0:
            raise unittest2.SkipTest("OpenVPN test machine already in use")
            
        appData = app.getSettings()
        appData["serverEnabled"]=True
        siteName = appData['siteName']
        appData['exports']['list'].append(create_export("192.0.2.0/24")) # append in case using LXC
        appData['remoteClients']['list'][:] = []  
        appData['remoteClients']['list'].append(setUpClient())
        #enable user/password authentication, set to AD directory
        appData['authUserPass']=True
        appData["authenticationType"]="ACTIVE_DIRECTORY"
        app.setSettings(appData)
        clientLink = app.getClientDistributionDownloadLink(vpnClientName,"zip")

        #download, unzip, move config to correct directory
        result = configureVPNClientForConnection(clientLink)
        assert(result == 0)
        
        #create credentials file containing username/password
        remote_control.run_command("echo " + global_functions.ad_user + " > /tmp/authUserPassFile; echo passwd >> /tmp/authUserPassFile", host=global_functions.vpnClientVpnIP)
        #connect to openvpn using the file
        remote_control.run_command("cd /etc/openvpn; sudo nohup openvpn --config " + siteName + ".conf --auth-user-pass /tmp/authUserPassFile >/dev/null 2>&1 &", host=global_functions.vpnClientVpnIP)

        timeout = waitForClientVPNtoConnect()
        # fail if tunnel doesn't connect
        assert(timeout > 0)
        # ping the test host behind the Untangle from the remote testbox
        result = remote_control.run_command("ping -c 2 " + remote_control.clientIP, host=global_functions.vpnClientVpnIP)
        
        listOfClients = app.getActiveClients()
        print("address " + listOfClients['list'][0]['address'])
        print("vpn address 1 " + listOfClients['list'][0]['poolAddress'])

        host_result = remote_control.run_command("host test.untangle.com", stdout=True)
        match = re.search(r'address \d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}', host_result)
        ip_address_testuntangle = (match.group()).replace('address ','')

        # stop the vpn tunnel on remote box
        remote_control.run_command("sudo pkill openvpn", host=global_functions.vpnClientVpnIP)
        # openvpn takes time to shut down
        time.sleep(3) 

        assert(result==0)
        assert(listOfClients['list'][0]['address'] == global_functions.vpnClientVpnIP)

        events = global_functions.get_events('OpenVPN','Connection Events',None,1)
        assert(events != None)
        found = global_functions.check_events( events.get('list'), 5,
                                            'remote_address', global_functions.vpnClientVpnIP,
                                            'client_name', vpnClientName )
        assert( found )

        # Check to see if the faceplate counters have incremented. 
        post_events_connect = global_functions.get_app_metric_value(app, "connect")
        assert(pre_events_connect < post_events_connect)
        
    @staticmethod
    def finalTearDown(self):
        global app, appWeb, appDC
        if app != None:
            uvmContext.appManager().destroy( app.getAppSettings()["id"] )
            app = None
        if appWeb != None:
            uvmContext.appManager().destroy( appWeb.getAppSettings()["id"] )
            appWeb = None
        if appDC != None:
            uvmContext.appManager().destroy( appDC.getAppSettings()["id"] )
            appDC = None

test_registry.registerApp("openvpn", OpenVpnTests)
