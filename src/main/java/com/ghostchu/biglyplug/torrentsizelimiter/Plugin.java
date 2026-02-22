package com.ghostchu.biglyplug.torrentsizelimiter;

import com.biglybt.core.logging.LogAlert;
import com.biglybt.core.logging.Logger;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.download.*;
import com.biglybt.pif.tag.Tag;
import com.biglybt.pif.ui.config.FloatParameter;
import com.biglybt.pif.ui.config.LongParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Getter
public class Plugin implements UnloadablePlugin, DownloadManagerListener, DownloadListener {
    private PluginInterface pluginInterface;
    private BasicPluginConfigModel configModel;
    private PluginConfig cfg;
    private LongParameter totalSizeLimitParam;
    private long totalSizeLimit;
    private LongParameter protectTimeHoursParam;
    private long protectTimeHours;
    private FloatParameter shareRatioThresholdParam;
    private float shareRatioThreshold = 1.5f;
    private StringParameter includeTagParam;
    private String includeTags;

    @Override
    public void unload() {

    }

    @Override
    public void initialize(PluginInterface pluginInterface) {
        this.pluginInterface = pluginInterface;
        this.cfg = pluginInterface.getPluginconfig();
        // convert from MB to Bytes
        this.totalSizeLimit = cfg.getPluginLongParameter("total-size-limit-mb", 0) * 1024 * 1024;
        this.shareRatioThreshold = cfg.getPluginFloatParameter("share-ratio-threshold", 1.5f);
        this.protectTimeHours = cfg.getPluginLongParameter("protect-time-hours", 0);
        this.includeTags = cfg.getPluginStringParameter("include-tags");
        configModel = pluginInterface.getUIManager().createBasicPluginConfigModel("torrentsizelimiter.configui");
        totalSizeLimitParam = configModel.addLongParameter2("total-size-limit-mb", "torrentsizelimiter.total-size-limit", totalSizeLimit);
        totalSizeLimitParam.addListener(lis -> {
            this.totalSizeLimit = totalSizeLimitParam.getValue() * 1024 * 1024;
            saveAndReload();
        });
        protectTimeHoursParam = configModel.addLongParameter2("protect-time-hours", "torrentsizelimiter.protect-time-hours", protectTimeHours);
        protectTimeHoursParam.addListener(lis -> {
            this.protectTimeHours = protectTimeHoursParam.getValue();
            saveAndReload();
        });
        shareRatioThresholdParam = configModel.addFloatParameter2("share-ratio-threshold", "torrentsizelimiter.share-ratio-threshold", shareRatioThreshold, 0.0f, Float.MAX_VALUE, true, 4);
        shareRatioThresholdParam.addListener(lis -> {
            this.shareRatioThreshold = shareRatioThresholdParam.getValue();
            saveAndReload();
        });
        includeTagParam = configModel.addStringParameter2("include-tags", "torrentsizelimiter.include-tags", includeTags);
        includeTagParam.addListener(lis -> {
            this.includeTags = includeTagParam.getValue();
            saveAndReload();
        });
        saveAndReload();
        pluginInterface.getDownloadManager().addListener(this);
    }


    private void saveAndReload() {
        cfg.setPluginParameter("total-size-limit-mb", this.totalSizeLimit / (1024 * 1024));
        try {
            this.cfg.save();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void stateChanged(Download download, int i, int i1) {

    }

    @Override
    public void positionChanged(Download download, int i, int i1) {

    }

    private boolean isTagged(Download download) {
        String[] tags = this.includeTags.split(",");
        for (Tag tag : download.getTags()) {
            for (String s : tags) {
                if (tag.getTagName().equalsIgnoreCase(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void downloadAdded(Download download) {
        if (isTagged(download)) {
            return;
        }
        long existsTaskSize = 0;
        Download[] existsDownloads = pluginInterface.getDownloadManager().getDownloads();
        for (Download task : existsDownloads) {
            if (task != download) {
                existsTaskSize += task.getTorrentSize();
            }
        }
        if (existsTaskSize + download.getTorrentSize() <= totalSizeLimit) {
            // do nothing
            return;
        }
        if (download.getTorrentSize() >= totalSizeLimit) {
            // 如果大于总限制，则直接拒绝
            Logger.log(new LogAlert(true, LogAlert.AT_INFORMATION, "Rejected: Size exceeds total size limit: " + download.getName() + "'."));
            try {
                download.stopAndRemove(true, true);
            } catch (DownloadException | DownloadRemovalVetoException e) {
                Logger.log(new LogAlert(true, LogAlert.AT_ERROR, "Failed to remove oversized torrent '" + download.getName() + "': " + e.getMessage(), e));
            }
            return;
        }

        long needToDeleteSize = existsTaskSize + download.getTorrentSize() - totalSizeLimit;
        List<Download> toRemove = new ArrayList<>();

        // 获取所有符合条件的候选任务
        long currentTime = System.currentTimeMillis();
        long protectTime = currentTime - (protectTimeHours * 60 * 60 * 1000);

        // 将候选任务分组：分享率超过1.0的和不超过1.0的
        List<Download> highRatioTasks = new ArrayList<>();
        List<Download> lowRatioTasks = new ArrayList<>();
        for (Download d : existsDownloads) {
            if (d == download) continue;
            if (!isTagged(d)) {
                continue;
            }
            if (d.getStats().getShareRatio() >= shareRatioThreshold * 1000) {
                highRatioTasks.add(d);
            } else {
                lowRatioTasks.add(d);
            }
        }

        lowRatioTasks.removeIf(d -> (currentTime - d.getCreationTime()) <= protectTime);

        // 排序规则：
        // 1. 优先删除分享率超过1.0的任务，按分享率降序排列（越大越优先）
        // 2. 其次删除分享率不足1.0的任务，按创建时间升序排列（越旧越优先）
        highRatioTasks.sort((d1, d2) -> {
            // 分享率高的优先
            int ratioCompare = Double.compare(d2.getStats().getShareRatio(), d1.getStats().getShareRatio());
            if (ratioCompare != 0) {
                return ratioCompare;
            }
            // 分享率相同时，旧的优先
            return Long.compare(d1.getCreationTime(), d2.getCreationTime());
        });
        // 旧的优先
        lowRatioTasks.sort(Comparator.comparingLong(Download::getCreationTime));

        // 先尝试从分享率高的任务中删除
        long deletedSize = 0;
        for (Download d : highRatioTasks) {
            toRemove.add(d);
            deletedSize += d.getTorrentSize();
            if (deletedSize >= needToDeleteSize) {
                break;
            }
        }

        // 如果还不够，从分享率低的任务中继续删除
        if (deletedSize < needToDeleteSize) {
            for (Download d : lowRatioTasks) {
                // 避免重复删除
                if (!toRemove.contains(d)) {
                    toRemove.add(d);
                    deletedSize += d.getTorrentSize();
                    if (deletedSize >= needToDeleteSize) {
                        break;
                    }
                }
            }
        }

        // 检查是否能够容纳新任务
        if (deletedSize >= needToDeleteSize) {
            // 删除选中的任务
            for (Download d : toRemove) {
                try {
                    Logger.log(new LogAlert(true, LogAlert.AT_INFORMATION,
                            "Ejected: '" + d.getName() + "' Share Ratio: " + d.getStats().getShareRatio() + ", Uploaded: " + d.getStats().getUploaded() + ", TTL: " + ((currentTime - d.getCreationTime()) / (60 * 60 * 1000)) + " hours."));
                    d.stopAndRemove(true, true);
                } catch (DownloadException | DownloadRemovalVetoException e) {
                    Logger.log(new LogAlert(true, LogAlert.AT_ERROR, "Failed to remove old torrent '" + d.getName() + "': " + e.getMessage(), e));
                }
            }
        } else {
            // 即使删除所有符合条件的任务也不够容纳新任务，拒绝新任务
            Logger.log(new LogAlert(true, LogAlert.AT_INFORMATION, "Rejected: Not enough space even after removing eligible old torrents '" + download.getName() + "'."));
            try {
                download.stopAndRemove(true, true);
            } catch (DownloadException | DownloadRemovalVetoException e) {
                Logger.log(new LogAlert(true, LogAlert.AT_ERROR, "Failed to remove new torrent '" + download.getName() + "': " + e.getMessage(), e));
            }
        }
    }

    @Override
    public void downloadRemoved(Download download) {

    }
}
