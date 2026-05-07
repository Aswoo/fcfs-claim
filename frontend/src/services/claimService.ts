import { api } from './api';
import { Product } from '../types';
import { getProductTheme } from '../constants/productThemes';

interface ProductApiResponse {
  id: number;
  name: string;
  description: string;
  stock: number;
  totalStock: number;
}

function toProduct(p: ProductApiResponse, index: number): Product {
  const theme = getProductTheme(index);

  let status: Product['status'] = 'available';
  if (p.stock === 0) status = 'sold_out';
  else if (p.stock / p.totalStock <= 0.3) status = 'low_stock';

  return {
    id: p.id,
    name: p.name,
    desc: p.description,
    stock: p.stock,
    total: p.totalStock,
    status,
    imageColor: theme.imageColor,
    imageColorEnd: theme.imageColorEnd,
  };
}

export const claimService = {
  getProducts: async (eventId: number): Promise<Product[]> => {
    const res = await api.get(`/api/v1/events/${eventId}/products`);
    return (res.data.data as ProductApiResponse[]).map(toProduct);
  },

  claim: async (userId: number, eventId: number, token: string, productId: number) => {
    const res = await api.post('/api/v1/claim', { userId, eventId, token, productId });
    return res.data as { success: boolean; data: { success: boolean; message: string } };
  },
};
