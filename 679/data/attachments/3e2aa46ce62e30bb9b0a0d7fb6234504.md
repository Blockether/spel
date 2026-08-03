## PATCH http://localhost:35147/echo → 200 OK

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
date: Mon, 03 Aug 2026 17:07:46 GMT
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
curl 'http://localhost:35147/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
