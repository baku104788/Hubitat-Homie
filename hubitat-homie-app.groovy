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


/****
TODO:
-add timeout on logging if set to trace?
-change listening functions to always publish to MQTT from within rather than returning a value when being called to sync
*****/


import groovy.transform.Field

@Field VERSION = 1

//These are the defined capability / attribute : publish_name	
@Field HOMIE_CUSTOM_CAPABILITY_PUBLISH = //Anything listed in here MUST have all attributes mapped explicitly.
	[												
    "ColorControl": //capability that we want to manually handle. Must specify each attribute we want
		[
			color : [
          attributeList : ["hue","saturation","level"],			//Attribute list needed to handle this capability
          publish_name : "color",														//This is name we are using to publish as. Also this is the name of the function that handles this attribute
      ],
			colorName : [//This is listed otherwise it would not be included.
      ],
		],
    "SwitchLevel": //capability
		[
			level : [
          publish_name : "dimmer"														//This is the function that will process incoming Hubitat events
      ],
		],
    "Refresh": [//capability specified like this without an attribute means it is skipped. (this would have been skipped by default as it has no attributes)
			//(this would have been skipped by default as it has no attributes)
		],
	]


//mapping of values that equate to true for sensors to become boolean
@Field ENUM_TRUE_MAP = 
[
	"contact": "open",
	"valve": "open",
	"motion": "active",
	"acceleration": "active",
	"colorMode" : "RGB",
	"presence" : "present",
	"holdableButton" : "true",
	"shock" : "detected",
	"tamper" : "detected",
	"water" : "wet",
  "lock" : "locked",
  "switch" : "on",
	"mute" : "muted"
]

@Field FORCE = 999
@Field TRACE = 4
@Field DEBUG = 3
@Field INFO =2
@Field WARN = 1
@Field ERROR = 0
@Field NONE = -1

@Field DEFAULT_HOMIE_DEV_NAME = "hubitat"
@Field mqttDeviceNetworkID = "homieMQTTconnection"


definition(
	name: "MQTT HOMIE App",
	namespace: "homiemqtt",
	author: "Jared Frank",
	importUrl: "https://hennyhaus.com",
	description: "Output Hubitat devices to MQTT with homie spec",
	category: "MQTT",
	singleInstance: "true",
	iconUrl: "",
	iconX2Url: "",
	iconX3Url: ""
)

preferences 
{
	page(name: "config")
}

def config()//{(settings?.port ? settings?.port :"1883")}
{
	dynamicPage(name: "main", title: "MQTT HOMIE App Configuration", uninstall: true, install: true){
		section("MQTT Broker Config", hideable: true,hidden: (settings?.broker ? true :false))
		{
		  input name: "broker", type: "text", title: "MQTT Broker IP Address", description: "", required: true
			input name: "port", type: "text", title: "MQTT Broker Port", description: "defaults to 1883", required: false, displayDuringSetup: true
		  input name: "username", type: "text", title: "MQTT Username", description: "(leave blank if none)", required: false
		  input name: "password", type: "password", title: "MQTT Password", description: "(leave blank if none)", required: false
			input name: "homiedevice",  type: "text", title: "Advertised homie device name", description: "defaults to hubitat (no spaces, alphanumeric and '-' only)", required: false
		}
		section("Misc app config", hideable: true, hidden: (settings?.broker ? true :false))
		{
			input "hue360", "bool", title: "MQTT hue numbers in 360 degrees?", required: false, devaultValue: true
			input "hub", "bool", title: "Publish Hub Status", required: false
		}
    section("Device selection") 
		{
			input "loglevel", "enum", title: "Log level", required: true, defaultValue: "WARN", options: ["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "NONE"]
      input "devList", "capability.*",hideWhenEmpty: false, multiple: true, required: false, title: "<b>Devices to publish</b>", submitOnChange: false
		}

	}
}

//HE APP BUILT IN FUNCTIONS****************************************************************************************************************************
def installed()
{
	logger("App installed",FORCE)
	updateLogLevel(settings?.loglevel)
	initDriver()
}

def updated()
{
  logger("App updated",FORCE)
	updateLogLevel(settings?.loglevel)
	initDriver()
}


def uninstalled() 
{
	if(getChildDevice(mqttDeviceNetworkID))
	{
		logger("Deleting MQTT device",INFO)
		try
		{
			deleteChildDevice(mqttDeviceNetworkID)
		}
		catch(e)
		{
			logger("${e}",ERROR)
			logger("UNABLE TO DELETE MQTT DEVICE",ERROR)
		}
	}
  logger("App uninstalled",FORCE)
}


//MQTT INCOMING SET HANDLING FUNCTIONS****************************************************************************************************************************
//Returns true if this attribute has a SET function defined.
def isSettable(property)
{
  return mqtt_set(null,property,null)
}

//Perform a set or if no node specified we just return if this attribute is settable or not
def mqtt_set(node, property, value)//TODO
{//return true if no node as this is just a check for the ability to set
	if(node)
	{
		mqttNode = getDeviceByMqttName(node)
		if(!mqttNode)
		{
			logger("Unable to process set for ${node} ${property}",ERROR)
			assert mqttNode
		}
	}
	
  switch(property)
  {
    case "color":
      if(!node) return true
      set_color(mqttNode,value)
      break
    case "colorTemperature":
      if(!node) return true
      set_colorTemperature(mqttNode,value)
      break
    case "dimmer":
      if(!node) return true
      set_dimmer(mqttNode,value)
      break
    case "lock":
      if(!node) return true
      set_lock(mqttNode,value)
      break
    case "switch":
      if(!node) return true
      set_switch(mqttNode,value)
      break
    default:
      if(node)//only show error if trying to control
				logger("MQTT set function not available for: ${property}",ERROR)
      return false
  }
}

//HUB VALUE PROCESSING************************************************************************************************************************
def hubMode(evt,devName=null,attribName = null)
{//this does MODE
	if(evt)
	{//publish to mqtt
		mqttPublish("${mqttTopicName("hub","mode")}","${location.getMode()}" ,true)
	}
	else
	{
		return location.getMode()
	}
}

def hubHsm(evt,devName=null,attribName = null)
{//this does HSM status
	if(evt)
	{//publish to mqtt
		mqttPublish("${mqttTopicName("hub","hsm")}","${location.hsmStatus}" ,true)
	}
	else
	{
		return location.hsmStatus
	}
}

//DEFAULT TYPE PROCESSING************************************************************************************************************************
def defaultHandler_datatype(devName,attribName)
{
  try
  {
    device = getDeviceByMqttName(devName)
    
    for (attrib in device.supportedAttributes)
    {
      if(attrib.name==attribName)
      {
        switch(attrib.dataType.toLowerCase())
        {
          case "enum":
            if(ENUM_TRUE_MAP.get(attribName))//Convert ENUM to BOOL
              return "boolean"
            else
              return "string"
            break
          case "number":
            return "float"
            break
          case "bool":
            return "boolean"
            break
          default:
            return "string"
        }
      }
    }
  }
  catch(ex)
  {
    logger("Error determining datatype for : ${devName}. Using String",ERROR)
    return "string"
  }
}

//DEFAULT DEVICES - Publish value to MQTT
def defaultHandler(evt,devName = null,attribName = null)//OK
{//MQTT PUBLISH ONLY ATTRIBUTES. NO MQTT SET PERMITTED. CONVERTED TO BOOL AS NEEDED PER ENUM_TRUE_MAP
	def attribute
	def currentValue
	
	if(evt)
  {
    attribute = evt.name
    //device = evt.getDevice()
    currentValue = evt.value
  }
  else
  {
    attribute = attribName
    device = getDeviceByMqttName(devName)
		currentValue = device.currentValue(attribName)
  }

	def result = "undef"
    
  if(ENUM_TRUE_MAP.get(attribute))//if this attribute exists with this map we can do true/false
  {
    if(currentValue == ENUM_TRUE_MAP.get(attribute))
      result = true
    else
      result = false
  }
  else
  {
    result = "${currentValue}"
  }
	

  if(evt)
  {
    mqttPublish("${mqttTopicID(evt.deviceId,attribute)}","${result}" ,true)
  }
  else
  {
    return(result)
  }

}

//COLOR TYPE HANDING************************************************************************************************************************
def color_datatype( devName,attribName)
{
	return "color"
}

def color(evt,devName=null,attribName = null)//OK
{
	def device
	def currentValue
	
  //PUBLISH TO MQTT
  if(evt)
  {
    device = evt.getDevice()
  }
  else//return the value
  {
    device = getDeviceByMqttName(devName)
  }
	
  hue = device.currentValue("hue")
	if(settings?.hue360==true)
	{//convert hue to 360
		hue = (hue*3.59)
	}
	
	saturation = device.currentValue("saturation")
  level = device.currentValue("level")

  currentValue = "${hue},${saturation},${level}"

  if(evt)
  {
    mqttPublish("${mqttTopicID(evt.deviceId,"color")}","${currentValue}" ,true)
  }
  else
  {
    return(currentValue)
  }
}

//DIMMER TYPE HANDING************************************************************************************************************************
def dimmer_datatype(devName,attribName)
{
	return "float"
}

def dimmer(evt,devName=null,attribName = null)//OK
{
	def device
	def currentValue
	
  //PUBLISH TO MQTT
  if(evt)
  {
    device = evt.getDevice()
  }
  else
  {
    device = getDeviceByMqttName(devName)
  }

  currentValue = (Double) device.currentValue("level") / 100
  
  if(evt)
  {
    mqttPublish("${mqttTopicID(evt.deviceId,"dimmer")}","${currentValue}" ,true)
  }
  else
  {
    return(currentValue)
  }
  
}

//SETTABLE TYPE FUNCTIONS************************************************************************************************************************
def set_color(device,value)//TODO
{
  devAttribs = device.supportedAttributes
  logger("dev: ${device}",TRACE)
  hue = devAttribs.currentValue("hue")
  saturation = devAttribs.currentValue("saturation")
  level = devAttribs.currentValue("level")
		
  logger("mqtt set processed ${device.displayName} : color = ${value}",TRACE)
}

def set_colorTemperature(device,value)//TODO
{
	device.setColorTemperature(value)	
  logger("mqtt set processed ${device.displayName} : colorTemperature = ${value}",TRACE)
}

def set_dimmer(device,value)//OK
{
  level = (Double.parseDouble(value) * 100).toInteger()
  device.setLevel(level,3)
  logger("mqtt set processed ${device.displayName} : dimmer = ${level}",TRACE)	
}

def set_lock(device,value)//OK
{
	switch(value.toLowerCase())
	{
		case "true":
			device.lock()
			break
		case "false":
			device.unlock()
			break
		default:
			logger("Invalid set command ${device.displayName} : lock = ${level}",ERROR)	
			return
	}
  logger("mqtt set processed ${device.displayName} : lock = ${level}",TRACE)	
}

def set_switch(device,value)//OK
{
	switch(value.toLowerCase())
	{
		case "true":
			device.on()
			break
		case "false":
			device.off()
			break
		default:
			logger("Invalid set command ${device.displayName} : switch = ${value}",ERROR)	
			return
	}
  logger("mqtt set processed ${device.displayName} : switch = ${value}",TRACE)		
}

def mqttPublish(String topic, String payload, boolean retained = false)
{
	try
	{
		getChildDevice(mqttDeviceNetworkID).mqttPublish(topic,payload,retained)
	}
	catch(ex)
	{
		logger("MQTT driver device not found. Re-run APP config.",ERROR)
	}
}


//CREATE CHILD DEVICE IF IT DOESN'T EXIST************************************************************************************************************************
def createMQTTdevice()
{
	logger("Creating MQTT device",INFO)

	if(!getChildDevice(mqttDeviceNetworkID))
	{//create the device
		try
		{
			addChildDevice("homiemqtt","Homie MQTT Driver", mqttDeviceNetworkID)
		}
		catch(e)
		{
			logger("${e}",ERROR)
			return false
		}
		logger("MQTT device created",TRACE)
	}
	else
	{//already exists
		logger("MQTT device already exists",TRACE)
		return true
	}
	return true
}

//VARIOUS GET FUNCTIONS FOR RETRIEVING DEVICE OBJECT*****************************************************************************************************************
def getDeviceByIndex(index)//By array index within the device list
{
  deviceList = (settings?.devList)
	if(index >=0 && index <deviceList.size())
	{
		device = deviceList[targetDevIndex]
		return device
	}
 	return false
}

def getDeviceByID(devId)//By the HE device ID
{
  targetDevIndex = state.id2index.get(devID).value
	device = getDeviceByIndex(targetDevIndex)
	
	if(device)
	{
		//logger("${devId} is ${device.displayName}",TRACE)
		return device
	}
	logger("Device with id ${devId} not found.",ERROR) 
 	return false
}

def getDeviceByMqttName(mqttName)//By the published device name
{
  targetDevIndex = state.name2index.get(mqttName)
	device = getDeviceByIndex(targetDevIndex)
	
	if(device)
	{
		//logger("${mqttName} is ${device.displayName}",TRACE)
		return device
	}
	logger("MQTT ${mqttName} not found.",ERROR) 
 	return false
}

//INITIALIZATION OF THE CHILD DEVICE / MQTT SETTINGS***************************************************************************************************************
def initDriver(delayConnect = false)
{
	logger ("Homie MQTT APP - Driver Configuration",INFO)
  unsubscribe()
	
	if(!createMQTTdevice())//check for child.
	{
		logger("Unable to create MQTT driver. Verify Driver code installed",ERROR)
		return 
	}

	getChildDevice(mqttDeviceNetworkID).updateLogLevel(state.logLevel)
	
	if(!settings?.broker)//advise configuration needed. 
	{
		logger("Broker configuration missing.",WARN)
		return
	}
	
	//*********************************************************setup MQTT variables for connection*********************************************************
  mqPort = (settings?.port ? settings?.port :"1883")
	
	homiedevice = "${(settings?.homiedevice ? legalizeString(settings?.homiedevice) : DEFAULT_HOMIE_DEV_NAME)}"

  state.mqClientID = (state.mqClientID ? state.mqClientID :"hubitat"+now())

	//update connection info
	getChildDevice(mqttDeviceNetworkID).setupConnectionString("tcp://${settings?.broker}:${mqPort}",state.mqClientID,homiedevice,settings?.username,settings?.password,VERSION)
	
	//listen for connection updates from the driver so we can auto reconnect upon failure
	subscribe(getChildDevice(mqttDeviceNetworkID),"mqtt", mqttEvents)		
	subscribe(getChildDevice(mqttDeviceNetworkID),"homie", homieEvents)	
	
	//***************************************************************attempt MQTT connection***************************************************************
	getChildDevice(mqttDeviceNetworkID).setHomie("unconfigured")
	
	getChildDevice(mqttDeviceNetworkID).connect()

}
	
//DISCOVER HOMIE TREE AND UPDATE HASHMAPS WITH DEVICES***************************************************************************************************************
def initHomie()
{
	logger("INIT HOMIE",TRACE)
	state.id2name = new HashMap()
	state.id2index = new HashMap()
	state.name2index = new HashMap()
	state.homieAttribsList = new HashMap()
	index = 0
	//************************************************************loop to setup event listeners************************************************************
	loopDev = (devList)     
	if (loopDev != null)
	{
		//look at all enabled devices
		loopDev.each
		{ device ->  
			nodeName = legalizeString(device.displayName)
			def String nodeId = device.getId()
			logger("CONFIGURING: ${device.displayName}, ${device.capabilities}",INFO)

			
			//store indexing so we can grab the devices quickly from the hash based on name				
			configuredProperties=new HashMap()
			unconfiguredProperties=[]

			//set up enabled devices
			device.capabilities.each 
			{ cap ->
				cap.attributes.each 
					{ attrib ->
						if( !configuredProperties.containsKey(attrib.name))//if this is already set up then skip
						{
							listener = assignListener(device,cap.name,attrib.name)
							if(listener)
							{
								configuredProperties.put(attrib.name,listener)
							}
							else
							{
									if( !unconfiguredProperties.contains(attrib.name))//only include in list once
										unconfiguredProperties.push(attrib.name)
							}
						}
            else//TODO
            {//key already exists make sure handler matches
							logger("Multiple capabilities support this attribute: ${device.displayName}, ${attrib.name}",WARN)
            }
					}
			}

			if(configuredProperties)//this device has properties to publish.  Yay. Otherwise who cares.
			{	
				state.id2name.put(nodeId,nodeName)//id to mqtt name device mapping+++
				state.name2index.put(nodeName,index)
				state.id2index.put(nodeId,index)
		
				state.homieAttribsList.put(nodeName, configuredProperties);//mqtt name to device mapping
				logger("CONFIGURED: ${configuredProperties}",DEBUG)

				if(unconfiguredProperties)
					logger("Ignored attributes via HOMIE_CUSTOM_CAPABILITY_PUBLISH : ${unconfiguredProperties}",TRACE)
			}
			else//this device has nothing to off us
			{
				logger("No configuration available for ${device.displayName}",WARN)
			}
			//always increase index to keep in sync
			index++
		}
	}
	
	if(settings?.hub)
	{
		subscribe(location.mode, hubMode)
		subscribe(location.hsmStatus, hubHsm)
	}
	
	if(!loopDev && !settings?.hub)
	{//no devices enabled. that is silly.
		logger("No devices configured.",WARN)
		getChildDevice(mqttDeviceNetworkID).disable()
		return
	}
	
	getChildDevice(mqttDeviceNetworkID).setHomie("configured")
	publishHomie()

}


//SETUP HUBITAT LISTENER FOR THE SPECIFIED CAPABILITY / ATTRIBUTE PAIR. DETERMINED VIA CUSTOM HASHMAP OR DEFAULT RETURN NAME OF LISTENER
def assignListener(dev,capName, attribName)
{
  publishName = attribName
  eventListener = "defaultHandler"
	custAtrribList = null
	
	try
	{
		if(HOMIE_CUSTOM_CAPABILITY_PUBLISH.get(capName))//check if we use custom mappings
		{
			try
			{
				custAttrib = (Map)HOMIE_CUSTOM_CAPABILITY_PUBLISH.get(capName).get(attribName)
			}
			catch(e)
			{
				custAttrib = HOMIE_CUSTOM_CAPABILITY_PUBLISH.get(capName).get(attribName)
			}
			if(custAttrib)
			{
				custListen = custAttrib.get("publish_name")
				custAtrribList = custAttrib.get("attributeList")
       if(custListen)
			  {
			  	eventListener = custListen//get specified lister name
        }
			}
			else
			{//if capability is listed in HOMIE_CUSTOM_CAPABILITY_PUBLISH then we only publish listed attributes
				return false
			}
		}

		if(custAtrribList)
		{
   	 for(item in custAtrribList)
    	{
     	 logger("Attrib list subscribe to ${dev} : ${item} : ${eventListener}",TRACE)
     	 subscribe(dev,"${item}",eventListener)
    	}
		}
		else
		{
     	 logger("Attrib subscribe to ${dev} : ${attribName} : ${eventListener}",TRACE)
     	 subscribe(dev,"${attribName}",eventListener)
		}
		return eventListener
	}
	catch(e)
	{
		logger("Exception caught in assignListener ${e}",ERROR)
   	return false
	}
}

//HOMIE TREE PUBLISHING***************************************************************************************************************

def publishHomie(fullPublish = true)
{
	pauseExecution(250)
	mqttDriver = getChildDevice(mqttDeviceNetworkID)

	//state doesn't update right away so this ensures 
	//getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName") = "${(settings?.homiedevice ? legalizeString(settings.homiedevice) :defaultHomieDeviceName)}"
	
	if(fullPublish)
	{
		mqttDriver.setHomie("publishing")

		//this section is for the Homie Device info 
		mqttDriver.mqttPublish("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/\$homie","3.0.1" ,true)
		mqttDriver.mqttPublish("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/\$state","init" ,true)
		mqttDriver.mqttPublish("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/\$name","${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}" ,true)
		mqttDriver.mqttPublish("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/\$extensions","" ,true)
		mqttDriver.mqttPublish("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/\$fw/version","${VERSION}" ,true)

		//build node list
		nodeString = ""
		first = true
		state.name2index.keySet().each
		{
			nodeString = nodeString + "${(first ? "" :",")}" +it
			first = false
		}
		
		if(settings?.hub)//publish hub if configured
		{
			//hub mode
			if(fullPublish)
			{
				nodeString = nodeString + "${(first ? "" :",")}" +"hub"

				mqttDriver.mqttPublish("${mqttTopicName("hub","\$name")}","hub" ,true)
				mqttDriver.mqttPublish("${mqttTopicName("hub","\$type")}","mode" ,true)
				mqttDriver.mqttPublish("${mqttTopicName("hub","\$properties")}","mode,hsm" ,true)
				mqttDriver.mqttPublish("${mqttTopicName("hub","mode","\$name")}","${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")} mode" ,true)
				mqttDriver.mqttPublish("${mqttTopicName("hub","mode","\$datatype")}","string" ,true)
				mqttDriver.mqttPublish("${mqttTopicName("hub","mode","\$settable")}","true" ,true)
				
				mqttDriver.mqttPublish("${mqttTopicName("hub","hsm","\$name")}","${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")} safey monitor" ,true)
				mqttDriver.mqttPublish("${mqttTopicName("hub","hsm","\$datatype")}","string" ,true)
				mqttDriver.mqttPublish("${mqttTopicName("hub","hsm","\$settable")}","false" ,true)				
				
			}
			mqttDriver.mqttPublish("${mqttTopicName("hub","mode")}",hubMode(null,"hub","mode") ,true)
			mqttDriver.mqttPublish("${mqttTopicName("hub","hsm")}",hubHsm(null,"hub","hsm") ,true)
			
			
		}
			

		mqttDriver.mqttPublish("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/\$nodes",nodeString,true)
	}
	
	//THIS SECTION IS FOR THE INDIVIDUAL NODES
	
	for(nodeName in state.homieAttribsList.keySet())
	{
		logger("HOMIE Config ${nodeName}")
		device = getDeviceByMqttName(nodeName)
		if(device)
		{
			
			def propertiesString = ""//used during full publish
			def first = true //using for properties string
			
			for(attribute in state.homieAttribsList.get(nodeName).keySet())
			{		
				listener = state.homieAttribsList.get(nodeName).get(attribute)
				
				publishedAs = (listener=="defaultHandler" ? attribute :listener)//if it is a custom item use publish its name rather than the attribute name
								
				if(fullPublish)
				{
					listenerDatatype="${listener}_datatype"
					
          mqttDriver.mqttPublish("${mqttTopicName(nodeName,publishedAs,"\$name")}","${nodeName} ${publishedAs}" ,true)
          mqttDriver.mqttPublish("${mqttTopicName(nodeName,publishedAs,"\$datatype")}","${this."$listenerDatatype"(nodeName,publishedAs)}" ,true)
          mqttDriver.mqttPublish("${mqttTopicName(nodeName,publishedAs,"\$settable")}","${isSettable(publishedAs) ? "true" :"false"}" ,true)
					propertiesString = propertiesString + "${(first ? "" :",")}" +publishedAs
          first = false
				}
				//publish the value
				mqttDriver.mqttPublish("${mqttTopicName(nodeName,attribute)}","${this."$listener"(null,nodeName,publishedAs)}" ,true)
				
			}
				
      if(fullPublish)
			{				
        mqttDriver.mqttPublish("${mqttTopicName(nodeName,"\$name")}","${nodeName}" ,true)
        mqttDriver.mqttPublish("${mqttTopicName(nodeName,"\$type")}","${device.getTypeName()}" ,true)
        mqttDriver.mqttPublish("${mqttTopicName(nodeName,"\$properties")}","${propertiesString}" ,true)
			}

			if(!mqttIsOnline())//continuously check for fail to end. hopeful it doesn't slow it too much
				break
		
		}
		else
		{
			logger("Error getting device ${nodeName} during homie publish",ERROR)
		}
	}
  
	
	if(mqttIsOnline())
	{
		mqttDriver.mqttPublish("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/\$state","ready" ,true)
		mqttDriver.setHomie("published")
		subscribeHomie()
	}
	else
	{
		return
	}
}




def subscribeHomie()//if connection disconnect resets subscribes so just restart them from 0
{
	pauseExecution(250)
	mqttDriver = getChildDevice(mqttDeviceNetworkID)
	mqttDriver.setHomie("subscribing")
	for(nodeName in state.homieAttribsList.keySet())
	{
		for(attribute in state.homieAttribsList.get(nodeName).keySet())
		{
			if(isSettable(attribute))
			{
				mqttDriver.mqttSubscribe("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/${nodeName}/+/set")	
				break//once we get one we exit for now since doing all of them
			}
		}
		if(!mqttIsOnline())//continuously check for fail to end. hopeful it doesn't slow it too much
			break
	}

	if(mqttIsOnline())
	{
		mqttDriver.setHomie("listening")
	}
	else
	{//went offline during publish so reset
		logger("Failure during subscribe",ERROR)
		return
	}
	logger("MQTT Homie configuration completed",INFO)
}



def mqttIsOnline()
{
	return getChildDevice(mqttDeviceNetworkID).currentValue("mqtt") == "connected"
}

//RECEIVER FOR INCOMING MQTT SUBSCRIBES FROM DRIVER***********************************************************************************************
def incommingMQTT(topicString,value)
{
	topic = topicString.split("/")
	if(topic[topic.length-1].toLowerCase()== "set" && topic.length == 5)//ensure this is a set command as intended
	{//set topic in the form homie / devicename / nodename / attributename / set
		logger("Processing SET ${topic} = ${value}",TRACE)
		mqtt_set(topic[2], topic[3], value)//TODO
	}
	else
	{//not set - what do we do with this? nothing! :D
		logger("Unhandled ${topic} = ${value}",ERROR)
	}
}

//MQTT TOPIC CREATION***************************************************************************************************************************
def mqttTopicID(nodeId,attribute,property=null)
{//need to convert the 
	def String stringID = nodeId
	nodeName = state.id2name.get(stringID)
	//logger("mqtt topic: NodeID=${nodeId} Attribute=${attribute} ${(property ? "property=${property}" :"")}",TRACE)	
	return ("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/${nodeName}/${attribute}${(property ? "/${property}" :"")}")
}

def mqttTopicName(nodeName,attribute,property=null)
{//need to convert the 
	logger("mqtt topic: NodeName=${nodeName} Attribute=${attribute} ${(property ? "property=${property}" :"")}",TRACE)	
	return ("homie/${getChildDevice(mqttDeviceNetworkID).currentValue("homieDeviceName")}/${nodeName}/${attribute}${(property ? "/${property}" :"")}")
}

//MQTT STRING LEGALIZATION - SWITCH ILLEGAL CHARS TO A '-'
def legalizeString(name) {
	name = name.replaceAll("[^a-zA-Z0-9]+","-").toLowerCase()
	return name ? name : undefined
}

//LOGGER CONFIG***************************************************************************************************************************
def updateLogLevel(desiredLevel)
{
	switch(desiredLevel)
	{
		case "INFO":
			state.logLevel = INFO
			break;
		case "DEBUG":
			state.logLevel = DEBUG
			break
		case "TRACE":
			state.logLevel = TRACE
			break
		case "WARN":
			state.logLevel = WARN
			break
		case "ERROR":
			state.logLevel = ERROR
			break
		case "NONE":
			state.logLevel = NONE
			break
		default:
			log.info "Invalid log level selected. Setting to DEBUG"
			state.logLevel = DEBUG
		break
	}
}

def logger(message, level = -1)
{
	if(level <= state.logLevel || level == FORCE)
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