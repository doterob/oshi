/**
 * Oshi (https://github.com/dblock/oshi)
 *
 * Copyright (c) 2010 - 2016 The Oshi Project Team
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Maintainers:
 * dblock[at]dblock[dot]org
 * widdis[at]gmail[dot]com
 * enrico.bianchi[at]gmail[dot]com
 *
 * Contributors:
 * https://github.com/dblock/oshi/graphs/contributors
 */
package oshi.hardware.platform.mac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import oshi.hardware.UsbDevice;
import oshi.hardware.common.AbstractUsbDevice;
import oshi.jna.platform.mac.CoreFoundation;
import oshi.jna.platform.mac.CoreFoundation.CFMutableDictionaryRef;
import oshi.jna.platform.mac.CoreFoundation.CFTypeRef;
import oshi.jna.platform.mac.IOKit;
import oshi.util.platform.mac.CfUtil;
import oshi.util.platform.mac.IOKitUtil;

public class MacUsbDevice extends AbstractUsbDevice {

    private static final long serialVersionUID = 2L;

    public MacUsbDevice(String name, String vendor, String vendorId, String productId, String serialNumber,
            UsbDevice[] connectedDevices) {
        super(name, vendor, vendorId, productId, serialNumber, connectedDevices);
    }

    /*
     * Maps to store information using RegistryEntryID as the key
     */
    private static Map<Long, String> nameMap = new HashMap<Long, String>();
    private static Map<Long, String> vendorMap = new HashMap<Long, String>();
    private static Map<Long, String> vendorIdMap = new HashMap<Long, String>();
    private static Map<Long, String> productIdMap = new HashMap<Long, String>();
    private static Map<Long, String> serialMap = new HashMap<Long, String>();
    private static Map<Long, List<Long>> hubMap = new HashMap<Long, List<Long>>();

    /**
     * {@inheritDoc}
     */
    public static UsbDevice[] getUsbDevices() {
        // Reusable buffer for getting IO name strings
        Pointer buffer = new Memory(128); // io_name_t is char[128]

        // Empty out maps
        nameMap.clear();
        vendorMap.clear();
        vendorIdMap.clear();
        productIdMap.clear();
        serialMap.clear();
        hubMap.clear();

        // Iterate over USB Controllers. All devices are children of one of
        // these controllers in the "IOService" plane
        List<Long> usbControllers = new ArrayList<Long>();
        IntByReference iter = new IntByReference();
        IOKitUtil.getMatchingServices("IOUSBController", iter);
        int device = IOKit.INSTANCE.IOIteratorNext(iter.getValue());
        while (device != 0) {
            // Unique global identifier for this device
            LongByReference id = new LongByReference();
            IOKit.INSTANCE.IORegistryEntryGetRegistryEntryID(device, id);
            usbControllers.add(id.getValue());

            // Get device name and store in map
            IOKit.INSTANCE.IORegistryEntryGetName(device, buffer);
            nameMap.put(id.getValue(), buffer.getString(0));
            // The only information we have in registry for this device is the
            // locationID. Use that to search for matching PCI device to obtain
            // more information.
            CFTypeRef ref = IOKit.INSTANCE.IORegistryEntryCreateCFProperty(device, CfUtil.getCFString("locationID"),
                    CfUtil.ALLOCATOR, 0);
            if (ref != null && ref.getPointer() != null) {
                getControllerIdByLocation(id.getValue(), ref);
            }
            CfUtil.release(ref);

            // Now iterate the children of this device in the "IOService" plane.
            // If device parent is root, link to the controller
            IntByReference childIter = new IntByReference();
            IOKit.INSTANCE.IORegistryEntryGetChildIterator(device, "IOService", childIter);
            int childDevice = IOKit.INSTANCE.IOIteratorNext(childIter.getValue());
            while (childDevice != 0) {
                // Unique global identifier for this device
                LongByReference childId = new LongByReference();
                IOKit.INSTANCE.IORegistryEntryGetRegistryEntryID(childDevice, childId);

                // Get this device's parent in the "IOUSB" plane
                IntByReference parent = new IntByReference();
                IOKit.INSTANCE.IORegistryEntryGetParentEntry(childDevice, "IOUSB", parent);
                // If the parent is not an IOUSBDevice (will be root), set the
                // parentId to the controller
                LongByReference parentId = new LongByReference();
                if (!IOKit.INSTANCE.IOObjectConformsTo(parent.getValue(), "IOUSBDevice")) {
                    parentId = id;
                } else {
                    // Unique global identifier for the parent
                    IOKit.INSTANCE.IORegistryEntryGetRegistryEntryID(parent.getValue(), parentId);
                }
                // Store parent in map
                if (!hubMap.containsKey(parentId.getValue())) {
                    hubMap.put(parentId.getValue(), new ArrayList<Long>());
                }
                hubMap.get(parentId.getValue()).add(childId.getValue());

                // Get device name and store in map
                IOKit.INSTANCE.IORegistryEntryGetName(childDevice, buffer);
                nameMap.put(childId.getValue(), buffer.getString(0));
                // Get vendor and store in map
                String vendor = IOKitUtil.getIORegistryStringProperty(childDevice, "USB Vendor Name");
                if (vendor != null) {
                    vendorMap.put(childId.getValue(), vendor);
                }
                // Get vendorId and store in map
                long vendorId = IOKitUtil.getIORegistryLongProperty(childDevice, "idVendor");
                if (vendorId != 0) {
                    vendorIdMap.put(childId.getValue(), String.format("%04x", 0xffff & vendorId));
                }
                // Get productId and store in map
                long productId = IOKitUtil.getIORegistryLongProperty(childDevice, "idProduct");
                if (productId != 0) {
                    productIdMap.put(childId.getValue(), String.format("%04x", 0xffff & productId));
                }
                // Get serial and store in map
                String serial = IOKitUtil.getIORegistryStringProperty(childDevice, "USB Serial Number");
                if (serial != null) {
                    serialMap.put(childId.getValue(), serial);
                }
                IOKit.INSTANCE.IOObjectRelease(childDevice);
                childDevice = IOKit.INSTANCE.IOIteratorNext(childIter.getValue());
            }
            IOKit.INSTANCE.IOObjectRelease(childIter.getValue());

            IOKit.INSTANCE.IOObjectRelease(device);
            device = IOKit.INSTANCE.IOIteratorNext(iter.getValue());
        }
        IOKit.INSTANCE.IOObjectRelease(iter.getValue());

        // Build tree and return
        List<UsbDevice> controllerDevices = new ArrayList<UsbDevice>();
        for (Long controller : usbControllers) {
            controllerDevices.add(getDeviceAndChildren(controller, "0000", "0000"));
        }
        return controllerDevices.toArray(new UsbDevice[controllerDevices.size()]);
    }

    /**
     * Looks up vendor and product id information for a USB Host Controller by
     * cross-referencing the location
     * 
     * @param id
     *            The global unique ID for the host controller used as a key for
     *            maps
     * @param locationId
     *            The locationID of this controller returned from the registry
     */
    private static void getControllerIdByLocation(long id, CFTypeRef locationId) {
        // Create a matching property dictionary from the locationId
        CFMutableDictionaryRef propertyDict = CoreFoundation.INSTANCE.CFDictionaryCreateMutable(CfUtil.ALLOCATOR, 0,
                null, null);
        CoreFoundation.INSTANCE.CFDictionarySetValue(propertyDict, CfUtil.getCFString("locationID"), locationId);
        CFMutableDictionaryRef matchingDict = CoreFoundation.INSTANCE.CFDictionaryCreateMutable(CfUtil.ALLOCATOR, 0,
                null, null);
        CoreFoundation.INSTANCE.CFDictionarySetValue(matchingDict, CfUtil.getCFString("IOPropertyMatch"), propertyDict);

        // search for all IOservices that match the locationID
        IntByReference serviceIterator = new IntByReference();
        IOKitUtil.getMatchingServices(matchingDict, serviceIterator);
        // getMatchingServices releases matchingDict
        CfUtil.release(propertyDict);

        // Iterate matching services looking for devices whose parents have the
        // vendor and device ids
        boolean found = false;
        int matchingService = IOKit.INSTANCE.IOIteratorNext(serviceIterator.getValue());
        while (matchingService != 0 && !found) {
            // Get the parent, which contains the keys we need
            IntByReference parent = new IntByReference();
            IOKit.INSTANCE.IORegistryEntryGetParentEntry(matchingService, "IOService", parent);
            // look up the vendor-id by key
            // vendor-id is a byte array of 4 bytes
            byte[] vid = IOKitUtil.getIORegistryByteArrayProperty(parent.getValue(), "vendor-id");
            if (vid != null && vid.length >= 2) {
                vendorIdMap.put(id, String.format("%02x%02x", vid[1], vid[0]));
                found = true;
            }
            // look up the device-id by key
            // device-id is a byte array of 4 bytes
            byte[] pid = IOKitUtil.getIORegistryByteArrayProperty(parent.getValue(), "device-id");
            if (pid != null && pid.length >= 2) {
                productIdMap.put(id, String.format("%02x%02x", pid[1], pid[0]));
                found = true;
            }
            // iterate
            matchingService = IOKit.INSTANCE.IOIteratorNext(matchingService);
        }
    }

    /**
     * Recursively creates MacUsbDevices by fetching information from maps to
     * populate fields
     * 
     * @param registryEntryId
     *            The device unique registry id.
     * @param vid
     *            The default (parent) vendor ID
     * @param pid
     *            The default (parent) product ID
     * @return A MacUsbDevice corresponding to this device
     */
    private static MacUsbDevice getDeviceAndChildren(Long registryEntryId, String vid, String pid) {
        String vendorId = vendorIdMap.containsKey(registryEntryId) ? vendorIdMap.get(registryEntryId) : vid;
        String productId = productIdMap.containsKey(registryEntryId) ? productIdMap.get(registryEntryId) : pid;
        List<Long> childIds = hubMap.containsKey(registryEntryId) ? hubMap.get(registryEntryId) : new ArrayList<Long>();
        List<MacUsbDevice> usbDevices = new ArrayList<MacUsbDevice>();
        for (Long id : childIds) {
            usbDevices.add(getDeviceAndChildren(id, vendorId, productId));
        }
        Collections.sort(usbDevices);
        return new MacUsbDevice(
                nameMap.containsKey(registryEntryId) ? nameMap.get(registryEntryId) : vendorId + ":" + productId,
                vendorMap.containsKey(registryEntryId) ? vendorMap.get(registryEntryId) : "", vendorId, productId,
                serialMap.containsKey(registryEntryId) ? serialMap.get(registryEntryId) : "",
                usbDevices.toArray(new UsbDevice[usbDevices.size()]));
    }
}
