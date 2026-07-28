## PATCH http://localhost:37395/echo → 200 OK

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
date: Tue, 28 Jul 2026 15:07:29 GMT
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
curl 'http://localhost:37395/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
