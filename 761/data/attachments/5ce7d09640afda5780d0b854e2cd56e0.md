## PUT http://localhost:44111/echo → 200 OK

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
date: Wed, 23 Sep 2026 12:25:54 GMT
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
curl 'http://localhost:44111/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
