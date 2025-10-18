package cn.flying.monitor.data.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * Pagination information for API responses
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaginationInfo {
    
    private int page;
    
    private int size;
    
    private long totalElements;
    
    private int totalPages;
    
    private boolean first;
    
    private boolean last;
    
    private boolean hasNext;
    
    private boolean hasPrevious;
    
    private String sortBy;
    
    private String sortDirection;
    
    // Constructors
    public PaginationInfo() {}
    
    public PaginationInfo(int page, int size, long totalElements) {
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = calculateTotalPages(totalElements, size);
        this.first = page == 0;
        this.last = page >= totalPages - 1;
        this.hasNext = page < totalPages - 1;
        this.hasPrevious = page > 0;
    }
    
    public PaginationInfo(int page, int size, long totalElements, String sortBy, String sortDirection) {
        this(page, size, totalElements);
        this.sortBy = sortBy;
        this.sortDirection = sortDirection;
    }
    
    // Getters and Setters
    public int getPage() {
        return page;
    }
    
    public void setPage(int page) {
        this.page = page;
        updateFlags();
    }
    
    public int getSize() {
        return size;
    }
    
    public void setSize(int size) {
        this.size = size;
        this.totalPages = calculateTotalPages(totalElements, size);
        updateFlags();
    }
    
    public long getTotalElements() {
        return totalElements;
    }
    
    public void setTotalElements(long totalElements) {
        this.totalElements = totalElements;
        this.totalPages = calculateTotalPages(totalElements, size);
        updateFlags();
    }
    
    public int getTotalPages() {
        return totalPages;
    }
    
    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
        updateFlags();
    }
    
    public boolean isFirst() {
        return first;
    }
    
    public void setFirst(boolean first) {
        this.first = first;
    }
    
    public boolean isLast() {
        return last;
    }
    
    public void setLast(boolean last) {
        this.last = last;
    }
    
    public boolean isHasNext() {
        return hasNext;
    }
    
    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }
    
    public boolean isHasPrevious() {
        return hasPrevious;
    }
    
    public void setHasPrevious(boolean hasPrevious) {
        this.hasPrevious = hasPrevious;
    }
    
    public String getSortBy() {
        return sortBy;
    }
    
    public void setSortBy(String sortBy) {
        this.sortBy = sortBy;
    }
    
    public String getSortDirection() {
        return sortDirection;
    }
    
    public void setSortDirection(String sortDirection) {
        this.sortDirection = sortDirection;
    }
    
    // Utility methods
    private int calculateTotalPages(long totalElements, int size) {
        if (size <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) totalElements / size);
    }
    
    private void updateFlags() {
        this.first = page == 0;
        this.last = page >= totalPages - 1;
        this.hasNext = page < totalPages - 1;
        this.hasPrevious = page > 0;
    }
    
    public int getOffset() {
        return page * size;
    }
    
    public int getNextPage() {
        return hasNext ? page + 1 : page;
    }
    
    public int getPreviousPage() {
        return hasPrevious ? page - 1 : page;
    }
    
    public boolean isEmpty() {
        return totalElements == 0;
    }
    
    public boolean hasContent() {
        return totalElements > 0;
    }
    
    public long getElementsOnCurrentPage() {
        if (isEmpty()) {
            return 0;
        }
        if (isLast()) {
            return totalElements - (long) page * size;
        }
        return size;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaginationInfo that = (PaginationInfo) o;
        return page == that.page &&
               size == that.size &&
               totalElements == that.totalElements &&
               Objects.equals(sortBy, that.sortBy) &&
               Objects.equals(sortDirection, that.sortDirection);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(page, size, totalElements, sortBy, sortDirection);
    }
    
    @Override
    public String toString() {
        return "PaginationInfo{" +
               "page=" + page +
               ", size=" + size +
               ", totalElements=" + totalElements +
               ", totalPages=" + totalPages +
               ", first=" + first +
               ", last=" + last +
               ", hasNext=" + hasNext +
               ", hasPrevious=" + hasPrevious +
               ", sortBy='" + sortBy + '\'' +
               ", sortDirection='" + sortDirection + '\'' +
               '}';
    }
}