## PATCH http://localhost:35531/echo → 200 OK

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
date: Fri, 11 Sep 2026 08:05:07 GMT
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
curl 'http://localhost:35531/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
