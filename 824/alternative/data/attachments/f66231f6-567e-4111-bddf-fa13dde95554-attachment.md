## DELETE http://localhost:41203/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 04 Nov 2026 11:27:07 GMT
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
curl 'http://localhost:41203/echo' \
  -X DELETE
```
