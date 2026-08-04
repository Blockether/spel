## PATCH http://localhost:46063/echo → 200 OK

### Request Body
```json
{
  "email": "eve@new.com"
}
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Tue, 04 Aug 2026 14:05:07 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "eve@new.com"
  }
}
```

### cURL
```bash
curl 'http://localhost:46063/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
