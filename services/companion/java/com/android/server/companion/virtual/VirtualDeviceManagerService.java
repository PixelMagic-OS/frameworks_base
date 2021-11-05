/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.companion.virtual;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.companion.CompanionDeviceManager.OnAssociationsChangedListener;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceManager;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.ExceptionUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


/** @hide */
@SuppressLint("LongLogTag")
public class VirtualDeviceManagerService extends SystemService {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "VirtualDeviceManagerService";
    private final Object mVirtualDeviceManagerLock = new Object();
    private final VirtualDeviceManagerImpl mImpl;

    /**
     * Mapping from CDM association IDs to virtual devices. Only one virtual device is allowed for
     * each CDM associated device.
     */
    @GuardedBy("mVirtualDeviceManagerLock")
    private final SparseArray<VirtualDeviceImpl> mVirtualDevices = new SparseArray<>();

    /**
     * Mapping from user ID to CDM associations. The associations come from
     * {@link CompanionDeviceManager#getAllAssociations()}, which contains associations across all
     * packages.
     */
    private final ConcurrentHashMap<Integer, List<AssociationInfo>> mAllAssociations =
            new ConcurrentHashMap<>();

    /**
     * Mapping from user ID to its change listener. The listeners are added when the user is
     * started and removed when the user stops.
     */
    private final SparseArray<OnAssociationsChangedListener> mOnAssociationsChangedListeners =
            new SparseArray<>();

    public VirtualDeviceManagerService(Context context) {
        super(context);
        mImpl = new VirtualDeviceManagerImpl();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.VIRTUAL_DEVICE_SERVICE, mImpl);
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        super.onUserStarting(user);
        synchronized (mVirtualDeviceManagerLock) {
            final CompanionDeviceManager cdm = getContext()
                    .createContextAsUser(user.getUserHandle(), 0)
                    .getSystemService(CompanionDeviceManager.class);
            final int userId = user.getUserIdentifier();
            mAllAssociations.put(userId, cdm.getAllAssociations());
            OnAssociationsChangedListener listener =
                    associations -> mAllAssociations.put(userId, associations);
            mOnAssociationsChangedListeners.put(userId, listener);
            cdm.addOnAssociationsChangedListener(Runnable::run, listener);
        }
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        super.onUserStopping(user);
        synchronized (mVirtualDeviceManagerLock) {
            int userId = user.getUserIdentifier();
            mAllAssociations.remove(userId);
            final CompanionDeviceManager cdm = getContext().createContextAsUser(
                    user.getUserHandle(), 0)
                    .getSystemService(CompanionDeviceManager.class);
            OnAssociationsChangedListener listener = mOnAssociationsChangedListeners.get(userId);
            if (listener != null) {
                cdm.removeOnAssociationsChangedListener(listener);
                mOnAssociationsChangedListeners.remove(userId);
            }
        }
    }

    private class VirtualDeviceImpl extends IVirtualDevice.Stub implements IBinder.DeathRecipient {

        private final AssociationInfo mAssociationInfo;

        private VirtualDeviceImpl(IBinder token, AssociationInfo associationInfo) {
            mAssociationInfo = associationInfo;
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mVirtualDevices.put(associationInfo.getId(), this);
        }

        @Override
        public int getAssociationId() {
            return mAssociationInfo.getId();
        }

        @Override
        public void close() {
            synchronized (mVirtualDeviceManagerLock) {
                mVirtualDevices.remove(mAssociationInfo.getId());
            }
        }

        @Override
        public void binderDied() {
            close();
        }
    }

    class VirtualDeviceManagerImpl extends IVirtualDeviceManager.Stub {

        @Override
        public IVirtualDevice createVirtualDevice(
                IBinder token, String packageName, int associationId) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                    "createVirtualDevice");
            if (!PermissionUtils.validatePackageName(getContext(), packageName, getCallingUid())) {
                throw new SecurityException(
                        "Package name " + packageName + " does not belong to calling uid "
                                + getCallingUid());
            }
            AssociationInfo associationInfo = getAssociationInfo(packageName, associationId);
            if (associationInfo == null) {
                throw new IllegalArgumentException("No association with ID " + associationId);
            }
            synchronized (mVirtualDeviceManagerLock) {
                if (mVirtualDevices.contains(associationId)) {
                    throw new IllegalStateException(
                            "Virtual device for association ID " + associationId
                                    + " already exists");
                }
                return new VirtualDeviceImpl(token, associationInfo);
            }
        }

        @Nullable
        private AssociationInfo getAssociationInfo(String packageName, int associationId) {
            final int callingUserId = getCallingUserHandle().getIdentifier();
            final List<AssociationInfo> associations =
                    mAllAssociations.get(callingUserId);
            if (associations != null) {
                final int associationSize = associations.size();
                for (int i = 0; i < associationSize; i++) {
                    AssociationInfo associationInfo = associations.get(i);
                    if (associationInfo.belongsToPackage(callingUserId, packageName)
                            && associationId == associationInfo.getId()) {
                        return associationInfo;
                    }
                }
            } else {
                Slog.w(LOG_TAG, "No associations for user " + callingUserId);
            }
            return null;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable e) {
                Slog.e(LOG_TAG, "Error during IPC", e);
                throw ExceptionUtils.propagate(e, RemoteException.class);
            }
        }

        @Override
        public void dump(@NonNull FileDescriptor fd,
                @NonNull PrintWriter fout,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), LOG_TAG, fout)) {
                return;
            }
            fout.println("Created virtual devices: ");
            synchronized (mVirtualDeviceManagerLock) {
                for (int i = 0; i < mVirtualDevices.size(); i++) {
                    VirtualDeviceImpl virtualDevice = mVirtualDevices.valueAt(i);
                    fout.printf("%d: %s\n", mVirtualDevices.keyAt(i), virtualDevice);
                }
            }
        }
    }
}
