## PATCH http://localhost:43743/echo → 200 OK

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
date: Sat, 21 Nov 2026 18:25:26 GMT
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
curl 'http://localhost:43743/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
