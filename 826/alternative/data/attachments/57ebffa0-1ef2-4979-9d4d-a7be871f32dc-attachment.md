## DELETE http://localhost:36221/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 04 Nov 2026 12:09:28 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:36221/echo' \
  -X DELETE
```
