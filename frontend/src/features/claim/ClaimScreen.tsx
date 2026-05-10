import React, { useState, useCallback, useEffect } from 'react';
import { View, FlatList, StyleSheet, Alert, ListRenderItem, ActivityIndicator, Text } from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { Colors } from '../../constants/colors';
import { Product } from '../../types';
import { RootStackParamList } from '../../navigation/AppNavigator';
import { claimService } from '../../services/claimService';
import {
  Header,
  Banner,
  SectionHeader,
  ProductCard,
  BottomActionBar,
} from '../../shared/components';

type Props = NativeStackScreenProps<RootStackParamList, 'Claim'>;

const ITEM_HEIGHT = 96;

export const ClaimScreen = ({ navigation, route }: Props) => {
  const { token, eventId, userId } = route.params;

  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [claiming, setClaiming] = useState(false);
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);

  useEffect(() => {
    claimService.getProducts(eventId).then(setProducts).finally(() => setLoading(false));
  }, [eventId]);

  const handleSelect = useCallback((product: Product) => {
    if (product.status === 'sold_out') return;
    setSelectedProduct(prev => (prev?.id === product.id ? null : product));
  }, []);

  const handleClaim = useCallback(async () => {
    if (!selectedProduct || claiming) return;
    setClaiming(true);
    try {
      await claimService.claim(userId, eventId, token, selectedProduct.id);
      navigation.replace('Success', { productName: selectedProduct.name });
    } catch (err: any) {
      const status = err?.response?.status;
      const msg: string | undefined = err?.response?.data?.error;
      if (status === 409) {
        const isSoldOut = msg?.includes('재고') ?? true;
        if (isSoldOut) {
          Alert.alert(
            '품절됐습니다',
            '해당 상품의 재고가 소진되었습니다.\n다른 상품을 선택해주세요.',
            [{ text: '확인', onPress: () => {
              claimService.getProducts(eventId).then(setProducts);
              setSelectedProduct(null);
            }}],
          );
        } else {
          Alert.alert('이미 수령하셨습니다', '이미 수령 완료된 이벤트입니다.');
        }
      } else if (status === 401) {
        Alert.alert('토큰 만료', '입장 시간이 만료되었습니다.', [
          { text: '확인', onPress: () => navigation.navigate('Enter') },
        ]);
      } else {
        Alert.alert('오류', '잠시 후 다시 시도해주세요.');
      }
    } finally {
      setClaiming(false);
    }
  }, [selectedProduct, claiming, userId, eventId, token, navigation]);

  const getItemLayout = useCallback(
    (_: unknown, index: number) => ({ length: ITEM_HEIGHT, offset: ITEM_HEIGHT * index, index }),
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

  const availableCount = products.filter(p => p.status !== 'sold_out').length;

  if (loading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={Colors.brandGreen} />
        <Text style={styles.loadingText}>상품 목록 불러오는 중...</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Header />
      <FlatList
        data={products}
        keyExtractor={keyExtractor}
        renderItem={renderItem}
        getItemLayout={getItemLayout}
        ListHeaderComponent={
          <>
            <Banner />
            <SectionHeader
              title="리워드 상품"
              count={`${availableCount}/${products.length}개 신청 가능`}
            />
          </>
        }
        contentContainerStyle={styles.listContent}
        style={styles.list}
      />
      <BottomActionBar selectedProduct={selectedProduct} onClaim={handleClaim} loading={claiming} />
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
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    backgroundColor: Colors.cream,
  },
  loadingText: {
    fontSize: 13,
    color: Colors.textMuted,
  },
});
