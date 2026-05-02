import React, { useState, useCallback } from 'react';
import { View, FlatList, StyleSheet, Alert, ListRenderItem } from 'react-native';
import { Colors } from '../../constants/colors';
import { Product } from '../../types';
import {
  Header,
  Banner,
  SectionHeader,
  ProductCard,
  BottomActionBar,
} from '../../shared/components';

const MOCK_PRODUCTS: Product[] = [
  {
    id: 1,
    name: '스타벅스 써머 텀블러',
    desc: '한정 수량 20개',
    stock: 15,
    total: 20,
    status: 'available',
    imageColor: '#D4E8D0',
    imageColorEnd: '#A8D5A2',
  },
  {
    id: 2,
    name: '스타벅스 에코백',
    desc: '한정 수량 20개',
    stock: 4,
    total: 20,
    status: 'low_stock',
    imageColor: '#F5E6C8',
    imageColorEnd: '#E8D0A0',
  },
  {
    id: 3,
    name: '스타벅스 머그컵',
    desc: '한정 수량 20개',
    stock: 0,
    total: 20,
    status: 'sold_out',
    imageColor: '#E8D5D5',
    imageColorEnd: '#D4B0B0',
  },
  {
    id: 4,
    name: '스타벅스 키링',
    desc: '한정 수량 20개',
    stock: 18,
    total: 20,
    status: 'available',
    imageColor: '#D5D8E8',
    imageColorEnd: '#B0B8D4',
  },
];

const ITEM_HEIGHT = 96;

export const ClaimScreen = () => {
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);

  const handleSelect = useCallback((product: Product) => {
    setSelectedProduct(prev => (prev?.id === product.id ? null : product));
  }, []);

  const handleClaim = useCallback(() => {
    if (!selectedProduct) return;
    Alert.alert(
      '신청 완료',
      `${selectedProduct.name} 선착순 신청이 완료되었습니다!`,
      [{ text: '확인', onPress: () => setSelectedProduct(null) }]
    );
  }, [selectedProduct]);

  const getItemLayout = useCallback(
    (_: unknown, index: number) => ({
      length: ITEM_HEIGHT,
      offset: ITEM_HEIGHT * index,
      index,
    }),
    []
  );

  const keyExtractor = useCallback((item: Product) => String(item.id), []);

  const renderItem: ListRenderItem<Product> = useCallback(
    ({ item }) => (
      <ProductCard
        product={item}
        selected={selectedProduct?.id === item.id}
        onPress={handleSelect}
      />
    ),
    [selectedProduct, handleSelect]
  );

  const availableCount = MOCK_PRODUCTS.filter(p => p.status !== 'sold_out').length;

  return (
    <View style={styles.container}>
      <Header />
      <FlatList
        data={MOCK_PRODUCTS}
        keyExtractor={keyExtractor}
        renderItem={renderItem}
        getItemLayout={getItemLayout}
        ListHeaderComponent={
          <>
            <Banner />
            <SectionHeader
              title="리워드 상품"
              count={`${availableCount}/${MOCK_PRODUCTS.length}개 신청 가능`}
            />
          </>
        }
        contentContainerStyle={styles.listContent}
        style={styles.list}
      />
      <BottomActionBar selectedProduct={selectedProduct} onClaim={handleClaim} />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: Colors.cream,
  },
  list: {
    flex: 1,
  },
  listContent: {
    paddingTop: 8,
    paddingBottom: 16,
  },
});
