export const PRODUCT_THEMES = [
  { imageColor: '#D4E8D0', imageColorEnd: '#A8D5A2' },
  { imageColor: '#F5E6C8', imageColorEnd: '#E8D0A0' },
  { imageColor: '#E8D5D5', imageColorEnd: '#D4B0B0' },
  { imageColor: '#D5D8E8', imageColorEnd: '#B0B8D4' },
];

export function getProductTheme(index: number) {
  return PRODUCT_THEMES[index % PRODUCT_THEMES.length];
}
