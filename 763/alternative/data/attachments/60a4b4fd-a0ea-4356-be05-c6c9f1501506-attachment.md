## PUT http://localhost:35175/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Thu, 24 Sep 2026 23:05:07 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:35175/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
