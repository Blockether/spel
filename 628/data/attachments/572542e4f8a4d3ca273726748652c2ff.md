## DELETE http://localhost:41643/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 08 Jul 2026 11:03:58 GMT
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
curl 'http://localhost:41643/echo' \
  -X DELETE
```
