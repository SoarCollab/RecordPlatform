<script lang="ts">
  import CalendarIcon from "@lucide/svelte/icons/calendar";
  import Clock from "@lucide/svelte/icons/clock";
  import {
    DateFormatter,
    getLocalTimeZone,
    parseDateTime,
    CalendarDateTime,
    type DateValue,
    today,
  } from "@internationalized/date";
  import { cn } from "$lib/utils";
  import { Button } from "$lib/components/ui/button";
  import { Calendar } from "$lib/components/ui/calendar";
  import * as Popover from "$lib/components/ui/popover";
  import { Input } from "$lib/components/ui/input";

  let {
    value = $bindable(),
    placeholder = "选择日期时间",
    class: className = undefined,
  } = $props();

  let open = $state(false);
  let date = $state<DateValue | undefined>(undefined);

  let hour = $state(0);
  let minute = $state(0);
  let second = $state(0);

  const df = new DateFormatter("zh-CN", {
    dateStyle: "medium",
    timeStyle: "medium",
    hour12: false,
  });

  // Init from value
  $effect(() => {
    if (value) {
      try {
        // Handle SQL format (replace space with T for parsing)
        const isoValue = value.replace(" ", "T");
        const parsed = parseDateTime(isoValue);
        date = parsed;
        hour = parsed.hour;
        minute = parsed.minute;
        second = parsed.second;
      } catch {
        // console.warn("Date parse error", e);
      }
    } else {
      if (date && date.toString().replace("T", " ") !== value) {
        date = undefined;
      }
    }
  });

  function updateValue() {
    if (date) {
      // Replace T with space for backend compatibility
      value = date.toString().replace("T", " ");
    } else {
      value = "";
    }
  }

  function handleDateChange(v: DateValue | undefined) {
    if (!v) {
      date = undefined;
      updateValue();
      return;
    }
    const newDate = new CalendarDateTime(
      v.year,
      v.month,
      v.day,
      hour,
      minute,
      second
    );
    date = newDate;
    updateValue();
  }

  function handleTimeChange() {
    if (!date) {
      const now = today(getLocalTimeZone());
      date = new CalendarDateTime(
        now.year,
        now.month,
        now.day,
        hour,
        minute,
        second
      );
    } else {
      date = new CalendarDateTime(
        date.year,
        date.month,
        date.day,
        hour,
        minute,
        second
      );
    }
    updateValue();
  }

  function limit(val: number, max: number) {
    if (isNaN(val)) return 0;
    return Math.max(0, Math.min(max, val));
  }
</script>

<Popover.Root bind:open>
  <Popover.Trigger>
    {#snippet child({ props })}
      <Button
        variant="outline"
        class={cn(
          "w-full justify-start text-left font-normal pl-3",
          !value && "text-muted-foreground",
          className
        )}
        {...props}
      >
        <CalendarIcon class="mr-2 h-4 w-4" />
        {#if value && date}
          {df.format(date.toDate(getLocalTimeZone()))}
        {:else}
          {placeholder}
        {/if}
      </Button>
    {/snippet}
  </Popover.Trigger>
  <Popover.Content class="w-auto p-0" align="start">
    <div class="p-0">
      <Calendar
        type="single"
        value={date as never}
        onValueChange={handleDateChange}
        initialFocus
      />
      <div class="border-t p-3 bg-muted/20">
        <div class="flex flex-col gap-2">
          <div class="flex items-center gap-2 mb-1">
            <Clock class="h-3.5 w-3.5 text-muted-foreground" />
            <span class="text-xs font-medium text-muted-foreground">时间</span>
          </div>
          <div class="flex items-center gap-1 justify-center">
            <div class="flex flex-col items-center gap-1">
              <Input
                type="number"
                class="h-8 w-14 text-center p-0 text-sm"
                min="0"
                max="23"
                bind:value={hour}
                onchange={() => {
                  hour = limit(hour, 23);
                  handleTimeChange();
                }}
              />
              <span class="text-[10px] text-muted-foreground">时</span>
            </div>
            <span class="text-lg pb-4 text-muted-foreground">:</span>
            <div class="flex flex-col items-center gap-1">
              <Input
                type="number"
                class="h-8 w-14 text-center p-0 text-sm"
                min="0"
                max="59"
                bind:value={minute}
                onchange={() => {
                  minute = limit(minute, 59);
                  handleTimeChange();
                }}
              />
              <span class="text-[10px] text-muted-foreground">分</span>
            </div>
            <span class="text-lg pb-4 text-muted-foreground">:</span>
            <div class="flex flex-col items-center gap-1">
              <Input
                type="number"
                class="h-8 w-14 text-center p-0 text-sm"
                min="0"
                max="59"
                bind:value={second}
                onchange={() => {
                  second = limit(second, 59);
                  handleTimeChange();
                }}
              />
              <span class="text-[10px] text-muted-foreground">秒</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Popover.Content>
</Popover.Root>
