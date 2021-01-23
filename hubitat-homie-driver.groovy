/********************************************************************************
    Hubitat MQTT Homie interface tailored towards integration with OpenHab
    Copyright (C) 2020  Jared Frank

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
********************************************************************************/
//VERSION 1.0
import groovy.transform.Field


//TODO add a heartbeat publish to catch disconnection - currently OH kludge does this

@Field VERSION = 1

metadata
{
  definition(
    name: "Homie MQTT Driver",
    namespace: "homiemqtt",
    author: "Jared Frank",
    importUrl: "https://hennyhaus.com",
    description: "Output Hubitat devices to MQTT with homie spec",
    category: "MQTT",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
  )
  {
		capability "Initialize"
    command "enable"
    command "disable"
		attribute "mqtt", "enum", ["disabled","disconnected","reconnecting","connected"]
		attribute "homie", "enum", ["unconfigured","configured","publishing","published","subscribing", "listening"]
		attribute "homieDeviceName", "string"
		attribute "logLevel", "integer"
  }
}

@Field LOGLEVELSETTING = 3

@Field FORCE = 999
@Field TRACE = 4
@Field DEBUG = 3
@Field INFO = 2
@Field WARN = 1
@Field ERROR = 0


def mqttSubscribe(String topic)
{
	if(interfaces.mqtt.isConnected())
	{
		interfaces.mqtt.subscribe(topic)
		logger("MQTT subscribed to: ${topic}",TRACE)
	}
	else
	{
		logger("MQTT unable to subscribe - not connected",ERROR)
		disconnect()//triggers reconnect
	}
	
}


def mqttPublish(String topic, String payload, boolean retained = false)
{
	try
	{
		if(interfaces.mqtt.isConnected())
		{
			logger("MQTT publish: ${topic}=${payload} ${(retained ? "RETAINED" :"")}",TRACE)
			interfaces.mqtt.publish( topic, payload, 1,retained)
		}
		else
		{
			logger("MQTT unable to publish - not connected",ERROR)
			if(device.currentValue("mqtt")!="reconnecting")
				reconnect()//triggers reconnect
		}
	}
	catch(ex)
	{
		logger("Invalid MQTT publish request: ${topic}=${payload}",WARN)
	}
}

def enable()
{
	if(device.currentValue("mqtt")=="disabled")
	{
		logger("Enabling MQTT Homie Driver",INFO)
		sendEvent (name: "mqtt", value: "disconnected")
		connect(true)
	}
}

def disable()
{
	if(device.currentValue("mqtt")!="disabled")
	{
		logger("Disabling MQTT Homie Driver",INFO)
		disconnect(false)
		sendEvent (name: "mqtt", value: "disabled")
		sendEvent (name: "homie", value: "unconfigured")
	}
}

def setHomie(status,statechange=true)
{
	switch(status)
	{
		case "unconfigured":
		case "configured":
		case "publishing":
		case "published":
		case "subscribing":
		case "listening":
			sendEvent (name: "homie", value: status, isStateChange: statechange)
			break
		default:
			logger("Invalid homie state ${status}",ERROR)
	}
}

def installed()
{
	logger("MQTT Driver device installed",FORCE)
	state.Driver_Version = VERSION
}

def uninstalled() 
{
	unschedule()
	disconnect()
  logger("MQTT Driver device uninstalled",FORCE)
}

def initialize()//this gets called at boot
{
	logger("Initializing Homie MQTT Driver",INFO)
	if(state.server)
		connect()
}

//********************************TO REMOVE WHEN OPENHAB FIXED https://github.com/openhab/openhab-addons/issues/7252********************************************
def repubName()
{	
	if(device.currentValue("homie")=="listening" && device.currentValue("mqtt")=="connected")
		mqttPublish("homie/${device.currentValue("homieDeviceName")}/\$heartbeat",now().toString() ,true) 
}
//********************************TO REMOVE WHEN OPENHAB FIXED https://github.com/openhab/openhab-addons/issues/7252********************************************

def updateConnectionStatus()
{
		sendEvent (name: "mqtt", value: "${(interfaces.mqtt.isConnected()?"connected":"disconnected")}")
}

def setupConnectionString(server,clientID,homieDeviceName,user=null,pass=null,appVersion) 
{
	logger("Storing MQTT connection details",TRACE)
  state.server = server
	sendEvent (name: "homieDeviceName", value: homieDeviceName)
	state.clientID = clientID
	state.user = user
	state.pass = pass
	state.App_Version = appVersion
}

def reconnect(retryCount=1, delay = 10000)
{
	logger("Retrying MQTT connection - attempt #${retryCount}",INFO)
	sendEvent (name: "mqtt", value: "reconnecting")
	pauseExecution(delay)
	connect(true)
	if(!interfaces.mqtt.isConnected())
	{//retry
		reconnect(retryCount+1)
	}
}



def connect(Boolean forceConnect = false)//OK
{
	if(device.currentValue("mqtt")!="disabled" || forceConnect)
	{
		logger("Attempting to connect to MQTT",DEBUG)

		if(interfaces.mqtt.isConnected())
		{//already connected
			logger("Disconnecting MQTT for reconect",TRACE)
			disconnect()
			pauseExecution(500) 
		}
		else
		{
			
		}

		try
		{
			interfaces.mqtt.connect(state.server, state.clientID ,state.user, state.pass, lastWillTopic: "/homie/${device.currentValue("homieDeviceName")}/\$state", lastWillQos: 0, lastWillMessage: "lost")
			logger("MQTT connect SUCCESS. LWT ${"/homie/${device.currentValue("homieDeviceName")}/\$state=lost"}",TRACE)
		}
		catch(e)
		{
			logger("${e}",ERROR)
			logger("MQTT connect FAIL")
		}	

		updateConnectionStatus()
		
		if(interfaces.mqtt.isConnected())
		{
			switch(device.currentValue("homie"))
			{
				case "unconfigured":
					parent.initHomie()
					break
				case "configured":
				case "publishing":
					parent.publishHomie()
					break
				case "published":
				case "subscribing":
				case "listening":
					parent.publishHomie(false)
					break
				default:
					break
			}
			
			//********************************TO REMOVE WHEN OPENHAB FIXED https://github.com/openhab/openhab-addons/issues/7252********************************************
			unschedule()
			schedule("0/30 * * * * ? *", repubName)
			//********************************TO REMOVE WHEN OPENHAB FIXED https://github.com/openhab/openhab-addons/issues/7252********************************************
		}
	}
	else
	{
		logger("MQTT Homie Driver disabled",WARN)
	}
}

def parse(String message)//OK
{//send subscription to parent
	topic = interfaces.mqtt.parseMessage(message).get("topic")
	payload = interfaces.mqtt.parseMessage(message).get("payload")
	logger("MQTT message ${topic} = ${payload}",TRACE)
	parent.incommingMQTT(topic,payload)
}

void mqttClientStatus(message)//TODO - handle errors
{
	if (message.indexOf(ERROR) == 0) 
	{
		try 
		{
			logger("MQTT client: ${message}",ERROR)
			reconnect()
		}
    catch (e)
    {
			logger("${e}",ERROR)
			if(device.currentValue("mqtt")!="reconnecting")
				reconnect()//triggers reconnect
    }
	}
  else 
	{
		logger("MQTT ${message}",INFO)
  }
}

def disconnect(updateStatus = true)//OK
{
	if(interfaces.mqtt.isConnected())
	{
		interfaces.mqtt.disconnect()
		logger("MQTT disconnected")
	}
	else
	{
		logger("MQTT already disconnected",WARN)
	}
	
	if(device.currentValue("homie")=="listening")//reset status
		sendEvent (name: "homie", value: "published")
	
	if(udpateStatus)
		updateConnectionStatus()
}

//LOGGER CONFIG***************************************************************************************************************************
def logger(message, level = -1)
{
	if(level <= LOGLEVELSETTING || level == FORCE)
	{
		switch(level)
		{
			case INFO:
				log.info "${message}"
				break;
			case DEBUG:
				log.debug "${message}"
				break
			case TRACE:
				log.trace "${message}"
				break
			case WARN:
				log.warn "${message}"
				break
			case ERROR:
				log.error "${message}"
				break
			default:
				log.info "<font color='orange'>${message}</font>"
			break
		}
	}
}