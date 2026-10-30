export const number = (value: number) =>
  value.toLocaleString("en-US", { maximumFractionDigits: 1 });
export const date = (value: string) =>
  new Date(value).toLocaleString("en-GB", {
    dateStyle: "medium",
    timeStyle: "medium",
  });
export const localNow = () =>
  new Date(Date.now() - new Date().getTimezoneOffset() * 60_000)
    .toISOString()
    .slice(0, 19);
