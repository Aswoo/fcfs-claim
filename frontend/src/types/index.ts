export type ProductStatus = 'available' | 'low_stock' | 'sold_out';

export interface Product {
  id: number;
  name: string;
  desc: string;
  stock: number;
  total: number;
  status: ProductStatus;
  imageColor: string;
  imageColorEnd: string;
}
