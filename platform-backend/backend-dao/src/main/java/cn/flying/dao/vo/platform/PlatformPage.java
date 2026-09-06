package cn.flying.dao.vo.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/** Retains the existing page contract while making only platform pagination counters safe JSON numbers. */
public final class PlatformPage<T> extends Page<T> {

    /** Rejects invalid page metadata before a response can begin serialization. */
    public PlatformPage(long current, long size, long total) {
        super(current, size, total);
        if (current < 1 || size < 1 || size > 100 || !PlatformSafeLongSerializer.isSafe(current)
                || !PlatformSafeLongSerializer.isSafe(total)) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
    }

    /** Copies an isolated tenant page into the platform-only transport representation. */
    public static <T> PlatformPage<T> from(IPage<T> source) {
        if (source == null) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
        PlatformPage<T> page = new PlatformPage<>(source.getCurrent(), source.getSize(), source.getTotal());
        page.setRecords(source.getRecords());
        return page;
    }

    /** Serializes the total count as a safely representable business number. */
    @Override
    @JsonSerialize(using = PlatformSafeLongSerializer.class)
    public long getTotal() {
        return super.getTotal();
    }

    /** Serializes the validated page size as a number. */
    @Override
    @JsonSerialize(using = PlatformSafeLongSerializer.class)
    public long getSize() {
        return super.getSize();
    }

    /** Serializes the current page as a safely representable number. */
    @Override
    @JsonSerialize(using = PlatformSafeLongSerializer.class)
    public long getCurrent() {
        return super.getCurrent();
    }

    /** Serializes the derived page count without inheriting the global Long-to-string policy. */
    @Override
    @JsonSerialize(using = PlatformSafeLongSerializer.class)
    public long getPages() {
        return super.getPages();
    }
}
